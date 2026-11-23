package com.cqgzs.cqlove.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.network.MapPreloadAckPayload;
import com.cqgzs.cqlove.network.MapProgressPayload;

/**
 * 客户端区块预加载状态机（只有客户端持有）。
 *
 * <p>为什么客户端要参与：Xaero 世界地图计算“记录范围”的公式是</p>
 * <pre>写入距离 = min(配置的 map_writing_distance, 32, min(客户端视距, 服务器下发视距))</pre>
 * <p>只提升服务器视距是不够的——客户端自己的视距（视频设置里的“渲染距离”）才是硬上限。
 * 所以预加载阶段这里会临时把客户端渲染距离提到 {@code 半径 + 2}（上限 32），
 * 让区块真正落到客户端，Xaero 才会把它们画进地图。</p>
 *
 * <p>另一个坑：Xaero 的 {@code MapWriter} 每次渲染只写 1 个区块，
 * 1000 个区块要几十秒。这段时间必须保持视距不变，写完后才恢复，否则客户端会按小视距
 * 把远处区块丢掉，地图留下空洞。所以这里按“帧”倒计时（Xaero 也是按帧写），
 * 倒计时结束后恢复视距并给服务端回确认包。</p>
 */
public final class ClientMapState {

    /** 写完后的提示保留帧数（约 1 秒） */
    private static final int FADE_OUT_FRAMES = 60;
    /** 渲染距离合法范围（原版限制） */
    private static final int MIN_RENDER_DISTANCE = 2;
    private static final int MAX_RENDER_DISTANCE = 32;
    /** Xaero 每帧写 1 个区块，留 20% 余量；再加固定缓冲帧 */
    private static final double WRITE_FRAMES_PER_CHUNK = 1.2D;
    private static final int WRITE_FRAMES_BUFFER = 40;

    /** 当前进度数据 */
    private static @Nullable MapProgressPayload current;
    /** 完成后进度条剩余显示帧数 */
    private static int fadeFrames;
    /** 写地图阶段剩余帧数 */
    private static int writeFramesLeft;
    /** 写地图阶段总帧数（用于算百分比） */
    private static int writeFramesTotal;
    /** 被临时改过的原渲染距离；-1 表示没有改过 */
    private static int savedRenderDistance = -1;
    /** 确认包是否已发（避免重复发） */
    private static boolean ackSent;

    private ClientMapState() {
    }

    /** 收到服务端进度包 */
    public static void onProgress(MapProgressPayload payload) {
        boolean wasFinished = current != null && current.finished();
        current = payload;

        if (!payload.finished()) {
            // 生成阶段：确保客户端视距足够
            expandClientViewDistance(payload.radius());
            writeFramesLeft = 0;
            writeFramesTotal = 0;
            ackSent = false;
            fadeFrames = 0;
            return;
        }

        // 生成完毕，进入“等 Xaero 写地图”阶段（重复收到 finished 时不重置倒计时）
        if (!wasFinished || writeFramesLeft <= 0) {
            int frames = (int) Math.ceil(payload.total() * WRITE_FRAMES_PER_CHUNK) + WRITE_FRAMES_BUFFER;
            if (savedRenderDistance < 0) {
                // 视距没提升说明半径本来就在视距内，区块早就写过了，短暂等待即可
                frames = Math.min(frames, WRITE_FRAMES_BUFFER);
            }
            writeFramesTotal = Math.max(1, frames);
            writeFramesLeft = writeFramesTotal;
            PlatformMods.LOGGER.info("[cqlove] 进入地图写入阶段: 区块={} 等待帧数={} 视距是否提升={}",
                    payload.total(), writeFramesTotal, savedRenderDistance >= 0);
        }
        fadeFrames = 0;
    }

    /** 每渲染帧调用一次（Xaero 也是每帧写 1 个区块，所以按帧计数最准） */
    public static void onFrame() {
        if (current == null) {
            return;
        }
        if (current.finished() && writeFramesLeft > 0) {
            if (--writeFramesLeft == 0) {
                finishWriting();
            }
            return;
        }
        if (current.finished()) {
            if (fadeFrames > 0) {
                fadeFrames--;
            } else {
                current = null; // 淡出结束，隐藏进度条
            }
        }
    }

    /** 地图写完了：恢复视距 + 通知服务端 */
    private static void finishWriting() {
        restoreRenderDistance();
        if (!ackSent) {
            ackSent = true;
            ClientPacketDistributor.sendToServer(new MapPreloadAckPayload());
            PlatformMods.LOGGER.info("[cqlove] 地图写入阶段结束, 已恢复视距并回执服务端");
        }
        fadeFrames = FADE_OUT_FRAMES;
    }

    /**
     * 临时提升客户端渲染距离，让远处区块真正落到客户端（Xaero 才会记录）。
     *
     * <p>必须同时 {@code broadcastOptions()}：服务端是按客户端上报的视距来决定发多少区块的，
     * 只改本地选项不发包，服务端仍然只发 16 个区块。</p>
     */
    private static void expandClientViewDistance(int radius) {
        if (savedRenderDistance >= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null || minecraft.player == null) {
            return;
        }
        int currentRenderDistance = minecraft.options.renderDistance().get();
        int target = Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, radius + 2));
        if (target <= currentRenderDistance) {
            return; // 原视距已经够用，不动玩家设置
        }
        savedRenderDistance = currentRenderDistance;
        minecraft.options.renderDistance().set(target);
        minecraft.options.broadcastOptions();
        ClientChat.message(Component.literal(
                "已临时把视距提升到 " + target + " 区块(预加载结束后自动恢复为 " + currentRenderDistance + ")")
                .withStyle(ChatFormatting.GRAY));
        PlatformMods.LOGGER.info("[cqlove] 临时提升客户端视距: {} -> {}", currentRenderDistance, target);
    }

    /** 恢复预加载前的渲染距离 */
    public static void restoreRenderDistance() {
        if (savedRenderDistance < 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int old = savedRenderDistance;
        savedRenderDistance = -1;
        if (minecraft.options != null) {
            minecraft.options.renderDistance().set(old);
            minecraft.options.broadcastOptions();
            PlatformMods.LOGGER.info("[cqlove] 已恢复客户端视距: {}", old);
        }
    }

    /** 退出世界/断线时复位（保证不会把玩家视距永久改大） */
    public static void reset() {
        restoreRenderDistance();
        current = null;
        fadeFrames = 0;
        writeFramesLeft = 0;
        writeFramesTotal = 0;
        ackSent = false;
    }

    /** 当前是否处于“地图写入中”阶段 */
    public static boolean isWriting() {
        return current != null && current.finished() && writeFramesLeft > 0;
    }

    /** 写地图阶段的百分比（0-100，估算值） */
    public static int writePercent() {
        if (writeFramesTotal <= 0) {
            return 100;
        }
        return (int) ((writeFramesTotal - writeFramesLeft) * 100L / writeFramesTotal);
    }

    /** 当前是否应该显示进度条 */
    public static boolean isVisible() {
        return current != null;
    }

    /** 当前进度数据（可能为 null） */
    public static @Nullable MapProgressPayload get() {
        return current;
    }
}
