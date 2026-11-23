package com.cqgzs.cqlove.magic;

import java.util.Map;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.network.MagicDataPayload;

/**
 * 魔法数据同步：把服务端的 {@link PlayerMagicData} 打包推给客户端（HUD / 技能栏 / 后续天赋界面用）。
 *
 * <p>只在数据真正变化时调用（登录、觉醒、学技能、施法、天赋加点），避免每 tick 刷包。</p>
 */
public final class MagicSync {

    private MagicSync() {
    }

    /** 全量同步一次 */
    public static void sync(ServerPlayer player) {
        if (player.hasDisconnected()) {
            return;
        }
        PlayerMagicData data = MagicData.get(player);
        long gameTime = player.level().getGameTime();

        StringBuilder awakened = new StringBuilder();
        for (MagicElement element : data.awakened()) {
            if (awakened.length() > 0) {
                awakened.append(',');
            }
            awakened.append(element.id());
        }

        StringBuilder selected = new StringBuilder();
        for (MagicElement element : data.awakened()) {
            String skillId = data.selectedId(element);
            if (skillId == null) {
                continue;
            }
            if (selected.length() > 0) {
                selected.append(',');
            }
            selected.append(element.id()).append('=').append(skillId);
        }

        StringBuilder cooldowns = new StringBuilder();
        for (Map.Entry<String, Long> entry : data.cooldowns().entrySet()) {
            long remain = entry.getValue() - gameTime;
            if (remain <= 0L) {
                continue;
            }
            if (cooldowns.length() > 0) {
                cooldowns.append(';');
            }
            cooldowns.append(entry.getKey()).append('|').append(remain);
        }

        StringBuilder talents = new StringBuilder();
        int[] levels = data.talentsCopy();
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) {
                talents.append(',');
            }
            talents.append(levels[i]);
        }

        PacketDistributor.sendToPlayer(player, new MagicDataPayload(
                data.rank().ordinal(),
                data.mana(),
                data.maxMana(),
                data.talentPoints(),
                awakened.toString(),
                selected.toString(),
                cooldowns.toString(),
                talents.toString(),
                String.join(",", data.learnedSkills())));
    }
}
