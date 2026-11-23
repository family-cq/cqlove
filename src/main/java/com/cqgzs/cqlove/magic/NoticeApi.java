package com.cqgzs.cqlove.magic;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.network.ClientNoticePayload;

/**
 * 统一提示 API：所有给玩家的文字反馈都走这里，客户端画在主界面中下 HUD，不进聊天框。
 *
 * <p>只用于文字提示（技能反馈、物品使用结果、警告等）。聊天内容本身不走这里。</p>
 */
public final class NoticeApi {

    private NoticeApi() {
    }

    /** 给单个玩家发一条提示 */
    public static void send(ServerPlayer player, Component text) {
        if (player == null || text == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ClientNoticePayload(text));
    }

    /** 给单个玩家发一条纯文本提示 */
    public static void send(ServerPlayer player, String text) {
        send(player, Component.literal(text));
    }
}
