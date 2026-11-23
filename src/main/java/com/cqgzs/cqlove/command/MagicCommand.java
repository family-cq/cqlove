package com.cqgzs.cqlove.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.config.CqloveConfig;
import com.cqgzs.cqlove.magic.MagicAttributes;
import com.cqgzs.cqlove.magic.MagicCaster;
import com.cqgzs.cqlove.magic.MagicData;
import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.magic.MagicRank;
import com.cqgzs.cqlove.magic.MagicSync;
import com.cqgzs.cqlove.magic.MagicTalent;
import com.cqgzs.cqlove.magic.PlayerMagicData;
import com.cqgzs.cqlove.magic.SkillDefinition;
import com.cqgzs.cqlove.magic.SkillRegistry;

/**
 * 魔法世界调试/管理指令：/magic ...
 *
 * <p>P 键天赋界面和 G 键轮盘做好之前，用这些指令可以完整走一遍流程（觉醒 → 学技能 → 加天赋 → 升阶）。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class MagicCommand {

    /** 系列 id 补全 */
    private static final SuggestionProvider<CommandSourceStack> ELEMENTS = (context, builder) -> {
        List<String> ids = new ArrayList<>();
        for (MagicElement element : MagicElement.values()) {
            ids.add(element.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    /** 天赋 id 补全 */
    private static final SuggestionProvider<CommandSourceStack> TALENTS = (context, builder) -> {
        List<String> ids = new ArrayList<>();
        for (MagicTalent talent : MagicTalent.values()) {
            ids.add(talent.name().toLowerCase(java.util.Locale.ROOT));
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    /** 技能 id 补全 */
    private static final SuggestionProvider<CommandSourceStack> SKILLS = (context, builder) -> {
        List<String> ids = new ArrayList<>();
        for (SkillDefinition skill : SkillRegistry.all()) {
            ids.add(skill.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private MagicCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        event.getDispatcher().register(Commands.literal("magic")
                .then(Commands.literal("awaken")
                        .then(Commands.argument("element", StringArgumentType.word())
                                .suggests(ELEMENTS)
                                .executes(MagicCommand::awaken)))
                .then(Commands.literal("learn")
                        .then(Commands.argument("skill", StringArgumentType.word())
                                .suggests(SKILLS)
                                .executes(MagicCommand::learn)))
                .then(Commands.literal("rank")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 3))
                                .executes(MagicCommand::rank)))
                .then(Commands.literal("talent")
                        .then(Commands.argument("talent", StringArgumentType.word())
                                .suggests(TALENTS)
                                .then(Commands.argument("points", IntegerArgumentType.integer(1, 200))
                                        .executes(MagicCommand::talent))))
                .then(Commands.literal("cooldown").executes(MagicCommand::clearCooldown))
                // 切换某系列当前释放的技能（G 轮盘做好前的替代手段）
                .then(Commands.literal("select")
                        .then(Commands.argument("element", StringArgumentType.word())
                                .suggests(ELEMENTS)
                                .then(Commands.argument("skill", StringArgumentType.word())
                                        .suggests(SKILLS)
                                        .executes(MagicCommand::select))))
                .then(Commands.literal("reset").executes(MagicCommand::reset))
                .then(Commands.literal("info").executes(MagicCommand::info)));
    }

    // ------------------------------------------------------------------
    // 子指令
    // ------------------------------------------------------------------

    private static int awaken(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        MagicElement element = MagicElement.byId(StringArgumentType.getString(context, "element"));
        if (element == null) {
            context.getSource().sendFailure(Component.literal("未知的魔法系列"));
            return 0;
        }
        String error = MagicCaster.awaken(player, element);
        if (error != null) {
            context.getSource().sendFailure(Component.literal(error));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("已觉醒 " + element.display()
                + " (按键 " + "ZXCV".charAt(Math.max(0, Math.min(3, MagicData.get(player).slotOf(element)))) + ")")
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.network.chat.TextColor.fromRgb(element.color()))), false);
        return 1;
    }

    private static int learn(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        String id = StringArgumentType.getString(context, "skill");
        SkillDefinition skill = SkillRegistry.byId(id);
        if (skill == null) {
            context.getSource().sendFailure(Component.literal("未知技能 id: " + id));
            return 0;
        }
        String error = MagicCaster.learn(player, skill);
        if (error != null) {
            context.getSource().sendFailure(Component.literal(error));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("已学会 " + skill.fullName()), false);
        return 1;
    }

    private static int rank(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        int value = IntegerArgumentType.getInteger(context, "value");
        PlayerMagicData data = MagicData.get(player);
        data.setRank(MagicRank.byOrdinal(value));
        MagicAttributes.apply(player);
        MagicSync.sync(player);
        context.getSource().sendSuccess(() -> Component.literal("阶级已设为 " + data.rank().display()), false);
        return 1;
    }

    private static int talent(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        MagicTalent talent;
        try {
            talent = MagicTalent.valueOf(StringArgumentType.getString(context, "talent").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal("未知天赋"));
            return 0;
        }
        int points = IntegerArgumentType.getInteger(context, "points");
        PlayerMagicData data = MagicData.get(player);
        data.setTalentPoints(Integer.MAX_VALUE);
        int added = data.addTalent(talent, points);
        data.setTalentPoints(0);
        MagicAttributes.apply(player);
        MagicSync.sync(player);
        context.getSource().sendSuccess(() -> Component.literal("天赋 " + talent.display() + " 已加到 " + data.talentLevel(talent)
                + " 级 (本次 +" + added + ")"), false);
        return 1;
    }

    /** 切换某系列当前释放的技能 */
    private static int select(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        MagicElement element = MagicElement.byId(StringArgumentType.getString(context, "element"));
        String skillId = StringArgumentType.getString(context, "skill");
        SkillDefinition skill = SkillRegistry.byId(skillId);
        if (element == null || skill == null) {
            context.getSource().sendFailure(Component.literal("未知的系列或技能"));
            return 0;
        }
        PlayerMagicData data = MagicData.get(player);
        if (!data.hasAwakened(element)) {
            context.getSource().sendFailure(Component.literal("还没有觉醒 " + element.display()));
            return 0;
        }
        if (skill.element() != element) {
            context.getSource().sendFailure(Component.literal("该技能不属于 " + element.display()));
            return 0;
        }
        if (!data.isLearned(skillId)) {
            context.getSource().sendFailure(Component.literal("还没有学会这个技能"));
            return 0;
        }
        data.selectSkill(element, skillId);
        MagicSync.sync(player);
        context.getSource().sendSuccess(() -> Component.literal(element.display() + " 当前技能已切换为 "
                + skill.name()), false);
        return 1;
    }

    private static int clearCooldown(CommandContext<CommandSourceStack> context) {        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        MagicData.get(player).clearCooldowns();
        MagicSync.sync(player);
        context.getSource().sendSuccess(() -> Component.literal("已清空全部技能冷却"), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        MagicData.get(player).resetAll();
        MagicAttributes.apply(player);
        MagicSync.sync(player);
        context.getSource().sendSuccess(() -> Component.literal("魔法数据已重置"), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        PlayerMagicData data = MagicData.get(player);
        StringBuilder builder = new StringBuilder();
        builder.append("阶级: ").append(data.rank().display())
                .append(" | 蓝量: ").append(String.format("%.1f/%.1f", data.mana(), data.maxMana()))
                .append(" | 魔力: ").append(String.format("%.1f", data.magicPower()))
                .append(" | 天赋点: ").append(data.talentPoints())
                .append("\n已觉醒: ");
        if (data.awakened().isEmpty()) {
            builder.append("无");
        } else {
            for (int i = 0; i < data.awakened().size(); i++) {
                MagicElement element = data.awakened().get(i);
                SkillDefinition selected = data.selectedSkill(element);
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append("ZXCV".charAt(i)).append('=').append(element.display())
                        .append('(').append(selected == null ? "未学技能" : selected.name()).append(')');
            }
        }
        builder.append("\n已学技能: ").append(data.learnedSkills().size()).append(" 个");
        context.getSource().sendSuccess(() -> Component.literal(builder.toString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
