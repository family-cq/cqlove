package com.cqgzs.cqlove.fx;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.network.VisualFxPayload;
import com.cqgzs.cqlove.fx.FxBillboard;
import com.cqgzs.cqlove.fx.FxBlock;

/**
 * 特效实体总入口（服务端侧）。
 *
 * <p>所有魔法特效实体都改成「<b>服务端只发包、客户端自己生成实体渲染</b>」之后，
 * 各系技能只需要调用这里的 {@link #billboard} / {@link #block} 拿到一个句柄，
 * 之后 {@code setPos} / {@code reshape} / {@code discard} 即可，与以前用
 * {@code Display.ItemDisplay} 的写法完全一致。</p>
 *
 * <p>相比原来的真实体方案，省掉了：服务端实体 tick、区块实体槽、实体追踪同步、
 * 服务端创建/销毁实体的开销；代价只是一个几十字节的自定义包。</p>
 */
public final class FxNet {

    /** 特效可见半径（格）：超出这个距离的玩家不需要知道这个特效 */
    private static final double VIEW_RADIUS = 96.0D;

    /** 服务端自增 id（客户端用它复用同一个 Display 实体） */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    /** 存活句柄：定期补发完整描述，覆盖丢包与中途进服 */
    private static final Set<FxHandle> ACTIVE = ConcurrentHashMap.newKeySet();

    private FxNet() {
    }

    /**
     * 生成一个「贴图面片」特效。
     *
     * @param level     所在服务端世界
     * @param pos       位置
     * @param item      承载贴图的物品（模型必须是平铺 / 竖直面片）
     * @param billboard 朝向模式（FIXED 固定 / CENTER 始终面向相机）
     */
    public static FxBillboard billboard(ServerLevel level, Vec3 pos, Item item,
                                        float scaleX, float scaleY, float scaleZ,
                                        Display.BillboardConstraints billboard,
                                        @Nullable Quaternionf rotation) {
        FxBillboard handle = new FxBillboard(level, NEXT_ID.getAndIncrement(), pos,
                new ItemStack(item), scaleX, scaleY, scaleZ, billboard, rotation);
        spawn(handle);
        return handle;
    }

    /** 生成一个「真实方块」特效 */
    public static FxBlock block(ServerLevel level, Vec3 pos, BlockState state,
                                float scaleX, float scaleY, float scaleZ,
                                @Nullable Quaternionf rotation, boolean glow) {
        FxBlock handle = new FxBlock(level, NEXT_ID.getAndIncrement(), pos, state,
                scaleX, scaleY, scaleZ, rotation, glow);
        spawn(handle);
        return handle;
    }

    /** 便捷重载：整块方块等比缩放 */
    public static FxBlock block(ServerLevel level, Vec3 pos, Block block, float scale, boolean glow) {
        return block(level, pos, block.defaultBlockState(), scale, scale, scale, null, glow);
    }

    /**
     * 生成一个「文本」特效（伤害数字）。
     *
     * @param lifeTicks 客户端兜底存活时长（短命特效不要设太大，否则丢包时会残留）
     */
    public static FxText text(ServerLevel level, Vec3 pos, net.minecraft.network.chat.Component text,
                              float scale, Display.BillboardConstraints billboard, int background,
                              int lifeTicks) {
        FxText handle = new FxText(level, NEXT_ID.getAndIncrement(), pos, text, scale,
                billboard, background, lifeTicks);
        spawn(handle);
        return handle;
    }

    private static void spawn(FxHandle handle) {
        ACTIVE.add(handle);
        handle.onRegistered();
        broadcast(handle.level, handle.position(), handle.spawnPayload());
    }

    static void unregister(FxHandle handle) {
        ACTIVE.remove(handle);
    }

    /** 发给附近所有玩家（按世界 + 距离过滤，不做实体追踪） */
    static void broadcast(ServerLevel level, Vec3 pos, VisualFxPayload payload) {
        double radiusSqr = VIEW_RADIUS * VIEW_RADIUS;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.x, pos.y, pos.z) <= radiusSqr) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /** 服务端每 tick 调用：短命特效到期自动回收，长命特效按 40 tick 节流补发 */
    public static void tickServer() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (FxHandle handle : ACTIVE) {
            if (handle.autoDiscardDue(handle.fxLevel().getGameTime())) {
                // ConcurrentHashMap.newKeySet 的迭代器是弱一致的，迭代中 remove 安全
                handle.discard();
            } else {
                handle.keepAliveIfDue();
            }
        }
    }

    /** 服务器关闭 / 重开：清掉句柄表（客户端退出世界时会自己清空） */
    public static void clearAll() {
        for (FxHandle handle : ACTIVE) {
            handle.alive = false;
        }
        ACTIVE.clear();
    }
}
