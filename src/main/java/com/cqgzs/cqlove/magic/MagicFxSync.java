package com.cqgzs.cqlove.magic;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.network.MagicFxPayload;

/**
 * 跟随特效状态广播：把“翅膀 / 法阵该不该显示”推给所有能看到该玩家的客户端。
 *
 * <p>翅膀和法阵现在是<b>客户端渲染层</b>（画在玩家模型上，贴身无延迟，见
 * {@code PlayerFxLayer}），服务端不再生成跟随实体，只需要在状态变化时广播一次开关位。
 * 为防止后进来的玩家错过广播，flags 非 0 时每 100 tick 补发一次（包很小，LAN 可忽略）。</p>
 */
public final class MagicFxSync {

    /** flags 非 0 时的补发间隔（tick），让中途进服/跨维度回来的玩家也能补到状态 */
    private static final long RESEND_INTERVAL = 100L;

    private MagicFxSync() {
    }

    /** 每玩家每 tick 调用（MagicEvents.onPlayerTick） */
    public static void tick(ServerPlayer player, PlayerMagicData data) {
        int flags = compute(data);
        long now = player.level().getGameTime();
        if (flags != data.fxFlagsSent() || (flags != 0 && now % RESEND_INTERVAL == 0L)) {
            data.fxFlagsSent(flags);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new MagicFxPayload(player.getUUID(), flags));
        }
    }

    /** 计算开关位：bit0 风之翼已学 / bit1 火神 / bit2 雷神（位定义见客户端 MagicClientFx） */
    private static int compute(PlayerMagicData data) {
        int flags = 0;
        if (data.isLearned("wind_2_1")) {
            flags |= 1;
        }
        if (data.fireGodActive()) {
            flags |= 2;
        }
        if (data.thunderGodActive()) {
            flags |= 4;
        }
        return flags;
    }
}
