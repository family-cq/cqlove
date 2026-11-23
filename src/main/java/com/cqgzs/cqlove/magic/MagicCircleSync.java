package com.cqgzs.cqlove.magic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.network.MagicCirclePayload;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 心灵系「圆圈 / 圆环」的服务端状态广播，机制和 {@link MagicCastRingSync}（超阶圆环）一致。
 *
 * <p>圆圈不再是服务端 {@code FxBillboard} 实体，而是客户端渲染层贴身绘制，
 * 所以玩家走动时用的是插值后的位置，不会拖尾、也不用每 tick 同步实体坐标。</p>
 *
 * <ul>
 *   <li>{@link #start} / {@link #stop} / {@link #clear}：只在「开启 / 结束」时发一次状态包；</li>
 *   <li>{@link #tick}：状态不变时每 40 tick 低频补发一次剩余 tick，
 *       让中途进服、跨维度回来、客户端时间漂移都能对齐（不是实时发送）；</li>
 *   <li>客户端自己按 tick 倒计时，即使补发包丢了也不会一直残留。</li>
 * </ul>
 *
 * <p>一个玩家可以同时存在多个圆圈，按 {@code variant} 分别记录。</p>
 */
public final class MagicCircleSync {

    /** 状态不变时的低频对齐间隔（tick） */
    private static final long RESEND_INTERVAL = 40L;

    /** 玩家 UUID -> (圆圈样式 -> 状态) */
    private static final Map<UUID, Map<Integer, Entry>> ACTIVE = new HashMap<>();

    private static final class Entry {
        final long endTick;
        final float radius;
        long lastSentEndTick;

        Entry(long endTick, float radius) {
            this.endTick = endTick;
            this.radius = radius;
            this.lastSentEndTick = endTick;
        }
    }

    private MagicCircleSync() {
    }

    /** 圆圈生成：立刻广播一次开启包（带完整时长），不等下一 tick */
    public static void start(ServerPlayer player, int variant, int ticks, float radius) {
        long end = player.level().getGameTime() + ticks;
        ACTIVE.computeIfAbsent(player.getUUID(), key -> new HashMap<>())
                .put(variant, new Entry(end, radius));
        send(player, variant, ticks, radius);
    }

    /** 单个圆圈结束：广播清除该样式 */
    public static void stop(ServerPlayer player, int variant) {
        Map<Integer, Entry> map = ACTIVE.get(player.getUUID());
        if (map != null) {
            map.remove(variant);
            if (map.isEmpty()) {
                ACTIVE.remove(player.getUUID());
            }
        }
        send(player, variant, 0, 0.0F);
    }

    /** 玩家下线 / 死亡 / 重登 / 关服：清状态并广播「全部清除」 */
    public static void clear(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        send(player, MagicCirclePayload.CLEAR_ALL, 0, 0.0F);
    }

    /** 每玩家每 tick 调用（{@code MagicEvents.onPlayerTick}），只做低频对齐，不逐 tick 发包 */
    public static void tick(ServerPlayer player) {
        Map<Integer, Entry> map = ACTIVE.get(player.getUUID());
        if (map == null || map.isEmpty()) {
            return;
        }
        long now = player.level().getGameTime();
        boolean resend = now % RESEND_INTERVAL == 0L;
        for (var iterator = map.entrySet().iterator(); iterator.hasNext(); ) {
            var record = iterator.next();
            int variant = record.getKey();
            Entry entry = record.getValue();
            int remaining = (int) (entry.endTick - now);
            if (remaining <= 0) {
                send(player, variant, 0, 0.0F);
                iterator.remove();
                continue;
            }
            if (entry.lastSentEndTick != entry.endTick || resend) {
                entry.lastSentEndTick = entry.endTick;
                send(player, variant, remaining, entry.radius);
            }
        }
        if (map.isEmpty()) {
            ACTIVE.remove(player.getUUID());
        }
    }

    /** 服务器关闭：只清服务端状态表（客户端退出世界时会自己 clear） */
    public static void clearAll() {
        ACTIVE.clear();
    }

    private static void send(ServerPlayer player, int variant, int remainingTicks, float radius) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new MagicCirclePayload(player.getUUID(), variant, remainingTicks, radius));
    }
}
