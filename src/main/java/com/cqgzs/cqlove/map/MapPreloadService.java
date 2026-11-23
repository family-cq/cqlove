package com.cqgzs.cqlove.map;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.network.MapProgressPayload;

/**
 * 区块预加载服务（需求文档 二.3.1 的服务端实现）。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>玩家执行 {@code /map come <num>}，以玩家所在区块为圆心、num 为半径收集区块列表（螺旋顺序，由近到远）；</li>
 *   <li>临时把服务器（玩家列表）视距提升到足以覆盖该半径（上限 32，原版上限），
 *       这样客户端能真正收到这些区块数据，Xaero 世界地图/小地图才能渲染出来；</li>
 *   <li>每 tick 用固定时间预算（{@link #TICK_BUDGET_NANOS}）批量强制生成/加载区块，避免一次性卡死；</li>
 *   <li>每 {@link #PROGRESS_INTERVAL_TICKS} tick 向发起玩家推送一次进度包；</li>
 *   <li>生成完毕后通知客户端进入“写地图”阶段，并保持视距提升，直到客户端回确认包
 *       （Xaero 每帧只写 1 个区块，过早恢复视距会让客户端丢弃远处区块，地图就白写了）；</li>
 *   <li>收到确认包（或超时）后恢复原视距。</li>
 * </ol>
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class MapPreloadService {

    /** 允许的最大区块半径（再大极易内存溢出/长时间卡顿） */
    public static final int MAX_RADIUS = 64;
    /** 原版服务器视距上限 */
    private static final int MAX_SERVER_VIEW_DISTANCE = 32;
    /** 每 tick 用于生成区块的时间预算：25ms（约 40 TPS 的余量，保证不把主线程彻底堵死） */
    private static final long TICK_BUDGET_NANOS = 25_000_000L;
    /** 至少每 tick 处理 1 个区块，防止单区块生成极慢时进度永远不动 */
    private static final int MIN_CHUNKS_PER_TICK = 1;
    /** 进度包发送间隔（tick） */
    private static final int PROGRESS_INTERVAL_TICKS = 4;
    /** 等待客户端“地图写完”确认的最长时间（tick）：5 分钟，超时则强制恢复视距 */
    private static final int ACK_TIMEOUT_TICKS = 6000;

    /** 当前任务；同一时间只允许一个（多人同时请求会互相拖慢） */
    private static @Nullable Task current;

    private MapPreloadService() {
    }

    /**
     * 发起一次预加载。
     *
     * @param player 发起指令的玩家（进度包只发给该玩家）
     * @param radius 区块半径
     */
    public static void start(ServerPlayer player, int radius) {
        if (current != null) {
            NoticeApi.send(player, Component.literal("已有区块预加载任务在进行中, 请等待其完成").withStyle(ChatFormatting.YELLOW));
            return;
        }
        int clamped = Math.max(1, Math.min(MAX_RADIUS, radius));
        ServerLevel level = player.level();
        ChunkPos center = player.chunkPosition();

        // 螺旋（由近及远）收集区块坐标，让玩家附近的区域先加载好
        List<ChunkPos> targets = spiral(center, clamped);

        // 临时提升视距：覆盖 半径 + 2 个区块的缓冲，最多到原版上限 32
        int wantedViewDistance = Math.min(MAX_SERVER_VIEW_DISTANCE, clamped + 2);
        int oldViewDistance = level.getServer().getPlayerList().getViewDistance();
        if (wantedViewDistance > oldViewDistance) {
            level.getServer().getPlayerList().setViewDistance(wantedViewDistance);
        }

        Task task = new Task(level, player, clamped, targets, oldViewDistance, wantedViewDistance > oldViewDistance);
        current = task;

        NoticeApi.send(player, Component.literal(
                "开始预加载区块: 半径 " + clamped + " 区块, 共 " + targets.size()
                        + " 个区块, 加载过程会卡顿, 请耐心等待").withStyle(ChatFormatting.AQUA));
        PlatformMods.LOGGER.info("[cqlove] 区块预加载开始: 玩家={} 圆心={} 半径={} 总数={}",
                player.getName().getString(), center, clamped, targets.size());

        // 立即推一次 0%，让进度条马上出现
        sendProgress(task, false);
    }

    /** 以 center 为圆心生成螺旋顺序的区块坐标列表（含圆心） */
    private static List<ChunkPos> spiral(ChunkPos center, int radius) {
        List<ChunkPos> list = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        // 曼哈顿/切比雪夫环逐圈外扩：第 d 圈加载距离为 d 的所有区块
        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    // 只取当前环（切比雪夫距离恰为 d），避免重复
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) {
                        continue;
                    }
                    list.add(new ChunkPos(center.x() + dx, center.z() + dz));
                }
            }
        }
        return list;
    }

    /** 每 tick 推进任务（服务端 tick 之后执行，避免与实体/方块逻辑抢占前半段预算） */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Task task = current;
        if (task == null) {
            return;
        }

        // 世界已卸载/玩家已离线：直接结束任务
        if (task.level.getServer() == null || task.requester.hasDisconnected()) {
            finish(task, "玩家已离开, 预加载中断");
            return;
        }

        // ---- 阶段一：正在生成区块 ----
        if (task.index < task.targets.size()) {
            long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
            int processed = 0;
            while (task.index < task.targets.size()) {
                ChunkPos pos = task.targets.get(task.index++);
                // loadOrGenerate=true：区块不存在则同步生成；返回值不用，只关心“已就绪”
                task.level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
                task.loaded++;
                processed++;
                if (processed >= MIN_CHUNKS_PER_TICK && System.nanoTime() >= deadline) {
                    break;
                }
            }

            // 进度推送（间隔控制，避免刷包）
            if (++task.ticksSinceProgress >= PROGRESS_INTERVAL_TICKS) {
                task.ticksSinceProgress = 0;
                sendProgress(task, false);
            }

            // 最后一个区块也生成完了：通知客户端“开始往 Xaero 地图里写”，并进入等待确认阶段
            if (task.index >= task.targets.size()) {
                sendProgress(task, true);
                NoticeApi.send(task.requester, Component.literal(
                        "区块生成完毕, 正在写入世界地图数据(视距已临时提升, 请稍候)").withStyle(ChatFormatting.AQUA));
                PlatformMods.LOGGER.info("[cqlove] 区块生成完毕, 等待客户端写入地图: 半径={} 总数={}",
                        task.radius, task.targets.size());
            }
            return;
        }

        // ---- 阶段二：等客户端把区块写进 Xaero 地图（视距必须保持提升，否则客户端会丢弃远处区块） ----
        if (!task.clientAck) {
            if (++task.ackTicks > ACK_TIMEOUT_TICKS) {
                finish(task, "等待客户端写入地图超时, 已恢复视距");
            }
            return;
        }

        // ---- 阶段三：收尾 ----
        restoreViewDistance(task);
        current = null;
        NoticeApi.send(task.requester, Component.literal(
                "区块预加载完成: 半径 " + task.radius + ", 共 " + task.targets.size() + " 个区块")
                .withStyle(ChatFormatting.GREEN));
        PlatformMods.LOGGER.info("[cqlove] 区块预加载完成: 半径={} 总数={}", task.radius, task.targets.size());
    }

    /**
     * 客户端回报“地图数据已写入完毕”。
     *
     * <p>只有当前任务的发起玩家才认，避免多人环境下别人的包误触发。</p>
     */
    public static void onClientAck(ServerPlayer player) {
        Task task = current;
        if (task == null || task.requester != player) {
            return;
        }
        task.clientAck = true;
    }

    /** 服务端关闭时清空任务，避免跨存档残留 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (current != null) {
            restoreViewDistance(current);
            current = null;
        }
    }

    /** 主动结束任务（异常/中断） */
    private static void finish(Task task, String reason) {
        sendProgress(task, true);
        restoreViewDistance(task);
        current = null;
        if (!task.requester.hasDisconnected()) {
            NoticeApi.send(task.requester, Component.literal(reason).withStyle(ChatFormatting.RED));
        }
        PlatformMods.LOGGER.warn("[cqlove] 区块预加载中止: {}", reason);
    }

    /** 恢复预加载前的服务器视距 */
    private static void restoreViewDistance(Task task) {
        if (!task.viewDistanceChanged) {
            return;
        }
        var server = task.level.getServer();
        if (server != null) {
            server.getPlayerList().setViewDistance(task.oldViewDistance);
        }
    }

    /** 给发起玩家推送进度 */
    private static void sendProgress(Task task, boolean finished) {
        if (task.requester.hasDisconnected()) {
            return;
        }
        PacketDistributor.sendToPlayer(task.requester,
                new MapProgressPayload(task.radius, task.targets.size(), task.loaded, finished));
    }

    /** 单个预加载任务的状态 */
    private static final class Task {
        final ServerLevel level;
        final ServerPlayer requester;
        final int radius;
        final List<ChunkPos> targets;
        final int oldViewDistance;
        final boolean viewDistanceChanged;

        int index = 0;
        int loaded = 0;
        int ticksSinceProgress = 0;
        /** 客户端是否已确认地图写完 */
        boolean clientAck = false;
        /** 等待客户端确认已过的 tick 数（用于超时保护） */
        int ackTicks = 0;

        Task(ServerLevel level, ServerPlayer requester, int radius, List<ChunkPos> targets,
             int oldViewDistance, boolean viewDistanceChanged) {
            this.level = level;
            this.requester = requester;
            this.radius = radius;
            this.targets = targets;
            this.oldViewDistance = oldViewDistance;
            this.viewDistanceChanged = viewDistanceChanged;
        }
    }
}
