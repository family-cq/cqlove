package com.cqgzs.cqlove.magic;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.network.CastRingPayload;

/**
 * 超阶技能脚下圆环的服务端状态广播。
 *
 * <p>需求：<b>圆环按技能时间持续，技能没进入冷却就不消失</b>。所以时长不再由客户端写死 2 秒，
 * 而是服务端在 {@link PlayerMagicData} 里持有「当前超阶技能 + 结束 tick」：</p>
 * <ul>
 *   <li>施法时 {@link PlayerMagicData#beginSuperRing} 开启（瞬发技能给 2 秒兜底）；</li>
 *   <li>持续型技能通过 {@link PlayerMagicData#lockSkill} 或
 *       {@link PlayerMagicData#holdSuperRing} 把圆环延长到效果结束；</li>
 *   <li>技能效果结束、写最终冷却时 {@link PlayerMagicData#endSuperRing} 立刻收掉圆环；</li>
 *   <li>本类每 tick 检测状态变化并广播，状态没变也定期补发剩余 tick，
 *       让中途进服 / 跨维度回来 / 客户端时间轻微漂移的情况都能对齐。</li>
 * </ul>
 *
 * <p>和 {@link MagicFxSync} 一样只发状态包，不生成实体，圆环贴身渲染不拖尾。</p>
 */
public final class MagicCastRingSync {

    /** 瞬发超阶技能（或技能生成失败）的兜底圆环时长：40 tick = 2 秒 */
    public static final int DEFAULT_RING_TICKS = 40;

    /** 状态不变时的补发间隔（tick）：包很小，2 秒一次即可 */
    private static final long RESEND_INTERVAL = 40L;

    private MagicCastRingSync() {
    }

    /** 每玩家每 tick 调用（{@code MagicEvents.onPlayerTick}） */
    public static void tick(ServerPlayer player, PlayerMagicData data) {
        long now = player.level().getGameTime();
        boolean active = data.superRingActive(now);
        int element = active ? data.superRingElement() : -1;
        long endTick = active ? data.superRingEndTick() : 0L;

        // 开启 / 结束 / 技能把时长延长了 -> 立刻广播；否则定期补发一次剩余时间
        boolean changed = element != data.superRingSentElement()
                || (active && endTick != data.superRingSentEndTick());
        boolean resend = active && now % RESEND_INTERVAL == 0L;
        if (changed || resend) {
            data.markSuperRingSent(element, endTick);
            send(player, element, active ? (int) Math.max(1L, endTick - now) : 0);
        }
    }

    /**
     * 施法当 tick 立即广播一次：技能 action 已经跑完，此时读到的结束 tick 就是最终时长，
     * 不必等下一 tick 的玩家 tick（避免圆环首帧只有 2 秒兜底值）。
     */
    public static void sendNow(ServerPlayer player, PlayerMagicData data, long now) {
        boolean active = data.superRingActive(now);
        int element = active ? data.superRingElement() : -1;
        long endTick = active ? data.superRingEndTick() : 0L;
        data.markSuperRingSent(element, endTick);
        send(player, element, active ? (int) Math.max(1L, endTick - now) : 0);
    }

    /**
     * 立刻清除某玩家在所有观察者客户端上的圆环（死亡 / 下线 / 重登时用）。
     * 同时把广播缓存复位，若技能其实还在持续（例如跨维度复制数据），下一 tick 会立刻补发开启包。
     */
    public static void clear(ServerPlayer player) {
        MagicData.get(player).markSuperRingSent(-1, 0L);
        send(player, -1, 0);
    }

    private static void send(ServerPlayer player, int elementOrdinal, int remainingTicks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new CastRingPayload(player.getUUID(), elementOrdinal, remainingTicks));
    }
}
