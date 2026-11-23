package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.cqgzs.cqlove.lan.PvpApi;

/**
 * 施法总控（需求文档 5.1.3 的“总体技能控制”落地）。
 *
 * <p>所有技能都必须经过这里，统一做：</p>
 * <ol>
 *   <li>是否已觉醒该系列 / 是否已学习该技能 / 阶级是否够</li>
 *   <li>冷却判定（<b>每个技能单独计时</b>，技能 id 作为 key）</li>
 *   <li>蓝量判定与扣除</li>
 *   <li>写冷却、执行技能、同步数据</li>
 * </ol>
 *
 * <p>施法时间（读条）暂时按瞬发处理，{@link SkillTier#castSeconds(MagicRank)} 已经算好，
 * 等 P 键天赋界面做好后再接读条与打断。</p>
 */
public final class MagicCaster {

    private MagicCaster() {
    }

    public static void castSlot(ServerPlayer player, int slot) {
        castSlot(player, slot, false);
    }

    /**
     * 按槽位释放技能：0=Z（第 1 个觉醒的系列）1=X 2=C 3=V。
     *
     * @param slot 按键槽位
     * @param ctrl 玩家是否按住 Ctrl（部分技能用它区分“对自己 / 对目标”释放）
     */
    public static void castSlot(ServerPlayer player, int slot, boolean ctrl) {
        PlayerMagicData data = MagicData.get(player);
        List<MagicElement> awakened = data.awakened();
        if (slot < 0 || slot >= awakened.size()) {
            NoticeApi.send(player, Component.literal("该按键还没有绑定魔法系列 (需先用觉醒书觉醒)")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        MagicElement element = awakened.get(slot);
        SkillDefinition skill = data.selectedSkill(element);
        if (skill == null) {
            NoticeApi.send(player, Component.literal(
                    "【" + element.display() + "】还没有学习技能 (使用天赋书学习)").withStyle(ChatFormatting.GRAY));
            return;
        }
        cast(player, skill, ctrl);
    }

    /**
     * 释放指定技能（服务端调用）。
     *
     * @return 是否成功释放
     */
    public static boolean cast(ServerPlayer player, SkillDefinition skill) {
        return cast(player, skill, false);
    }

    /**
     * 释放指定技能（服务端调用）。
     *
     * @param ctrl 玩家是否按住 Ctrl（传给 SkillContext，技能自行决定用途）
     * @return 是否成功释放
     */
    public static boolean cast(ServerPlayer player, SkillDefinition skill, boolean ctrl) {
        PlayerMagicData data = MagicData.get(player);
        long gameTime = player.level().getGameTime();
        // 云冰晶界：写入当前 tick，保证 setCooldown 里的"冷却 -50%"按准确的剩余时长计算
        data.noteGameTime(gameTime);

        // 1. 阶级限制：低阶玩家不能放高阶技能
        if (skill.tier().ordinal() > data.rank().maxLearnableTier()) {
            NoticeApi.send(player, Component.literal(
                    "阶级不足: 需要 " + skill.tier().display() + " 才能释放【" + skill.name() + "】")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        // 2. 技能锁定：风系持续技能（加速 / 风之翼 / 泯风千刃）使用期间只锁它自己，
        //    其他技能（含同系列超阶）照常释放
        if (data.isSkillLocked(skill.id(), gameTime)) {
            NoticeApi.send(player, Component.literal(String.format(
                    "【%s】技能锁定中: 还剩 %.1f 秒", skill.name(),
                    data.lockRemaining(skill.id(), gameTime) / 20.0D))
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        // 3. 冷却：每个技能独立计时
        long remain = data.cooldownRemaining(skill.id(), gameTime);
        if (remain > 0L) {
            NoticeApi.send(player, Component.literal(String.format(
                    "【%s】冷却中: %.1f 秒", skill.name(), remain / 20.0D)).withStyle(ChatFormatting.YELLOW));
            return false;
        }

        // 4. 蓝量
        int cost = skill.manaCost();
        if (data.mana() < cost) {
            NoticeApi.send(player, Component.literal(String.format(
                    "蓝量不足: 需要 %d, 当前 %.1f", cost, data.mana())).withStyle(ChatFormatting.RED));
            return false;
        }

        // 5. 扣蓝 + 写冷却（冷却时长取决于“玩家阶级 + 技能阶”）
        data.setMana(data.mana() - cost);
        int cooldownSeconds = skill.cooldownSeconds(data.rank());
        // 雷神降临 buff：仅当"只觉醒了雷系"时，冷却 -50%（文档：有其他系列则不享受）
        if (data.thunderGodActive() && data.awakened().size() <= 1) {
            cooldownSeconds = Math.max(1, cooldownSeconds / 2);
        }
        data.setCooldown(skill.id(), gameTime + cooldownSeconds * 20L);

        // 5.5 超阶技能：先开脚下圆环（技能 action 内部会通过技能锁定 / buff 时长把圆环延长到效果结束）
        if (skill.tier() == SkillTier.SUPER) {
            data.beginSuperRing(skill.id(), skill.element().ordinal(), gameTime,
                    MagicCastRingSync.DEFAULT_RING_TICKS);
        }

        // 6. 执行技能
        try {
            skill.action().cast(new SkillContext(player, skill, ctrl));
        } catch (Exception exception) {
            // 单个技能出错不能影响服务器主线程
            com.cqgzs.cqlove.PlatformMods.LOGGER.error("[cqlove] 技能释放异常: {}", skill.id(), exception);
        }

        // 6.5 超阶技能：广播脚下系列色圆环。此时 action 已经跑完，携带的是技能真实持续时间；
        //     技能没进入冷却前，后续每 tick 由 MagicCastRingSync 持续补发，圆环不会消失
        if (skill.tier() == SkillTier.SUPER) {
            MagicCastRingSync.sendNow(player, data, gameTime);
        }

        // 7. 同步给客户端（蓝量、冷却）
        MagicSync.sync(player);
        return true;
    }

    /**
     * 学习技能（天赋书/指令调用）。
     *
     * @return 失败原因，null 表示成功
     */
    public static String learn(ServerPlayer player, SkillDefinition skill) {
        PlayerMagicData data = MagicData.get(player);
        if (!data.hasAwakened(skill.element())) {
            return "还没有觉醒【" + skill.element().display() + "】";
        }
        if (data.isLearned(skill.id())) {
            return "已经学会【" + skill.name() + "】了";
        }
        if (skill.tier().ordinal() > data.rank().maxLearnableTier()) {
            return "阶级不足: 当前 " + data.rank().display() + ", 只能学习 "
                    + SkillTier.byOrdinal(data.rank().maxLearnableTier()).display() + " 及以下技能";
        }
        data.learn(skill.id());
        MagicSync.sync(player);
        return null;
    }

    /**
     * 觉醒一个系列（觉醒书/指令调用）。
     *
     * @return 失败原因，null 表示成功
     */
    public static String awaken(ServerPlayer player, MagicElement element) {
        PlayerMagicData data = MagicData.get(player);
        if (data.hasAwakened(element)) {
            return "已经觉醒过【" + element.display() + "】了";
        }
        if (!data.canAwaken()) {
            return "最多只能觉醒 " + Math.min(PlayerMagicData.MAX_SERIES, data.rank().maxSeries())
                    + " 个系列 (当前阶级 " + data.rank().display() + ")";
        }
        if (!data.awaken(element)) {
            return "觉醒失败";
        }
        MagicSync.sync(player);
        // 顺便把 PVP API 说明印一下，避免玩家以为技能对玩家没伤害是 bug
        if (!PvpApi.isPvpAllowed()) {
            NoticeApi.send(player, Component.literal("提示: 当前 PVP 关闭, 技能对玩家不造成伤害")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return null;
    }
}
