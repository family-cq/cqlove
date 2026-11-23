package com.cqgzs.cqlove.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.magic.SkillDefinition;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 魔法世界 HUD：
 * <ul>
 *   <li>蓝量：主界面饱食度上方一排“蓝心”，1 颗心 = 2 点蓝量，半颗 = 1 点，每排 10 颗（需求 5.6）</li>
 *   <li>技能栏：屏幕左下角 4 行文字，逐行显示 Z/X/C/V 四个槽位的“按键名: 当前选中技能 [剩余冷却]”，
 *       按键名实时读取按键绑定（玩家改键后跟着变），未觉醒/未学技能显示“无技能”，无冷却时不显示冷却</li>
 * </ul>
 */
@EventBusSubscriber(modid = PlatformMods.MODID, value = Dist.CLIENT)
public final class MagicHud {

    private static final Identifier MANA_LAYER = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_mana");
    private static final Identifier SKILL_LAYER = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_skill_bar");

    /** 9x9 像素心形（'#' 为实心） */
    private static final String[] HEART = {
            " ### ### ",
            "#########",
            "#########",
            "#########",
            "#########",
            " ####### ",
            "  #####  ",
            "   ###   ",
            "    #    "
    };
    /** 每排最多 10 颗心 */
    private static final int HEARTS_PER_ROW = 10;
    /** 心间距 */
    private static final int HEART_SIZE = 9;

    private static final int MANA_FULL = 0xFF3B7BFF;
    private static final int MANA_FULL_LIGHT = 0xFF7FB0FF;
    private static final int MANA_EMPTY = 0xFF152033;

    /** 技能槽文字：左边距与底部间距 */
    private static final int SLOT_TEXT_LEFT = 4;
    private static final int SLOT_TEXT_BOTTOM = 4;
    private static final int SLOT_LINE_HEIGHT = 10;

    private MagicHud() {
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 蓝量画在饱食度上面
        event.registerAbove(VanillaGuiLayers.FOOD_LEVEL, MANA_LAYER, MagicHud::renderMana);
        // 技能栏画在热键栏上面
        event.registerAbove(VanillaGuiLayers.HOTBAR, SKILL_LAYER, MagicHud::renderSkillBar);
    }

    // ------------------------------------------------------------------
    // 蓝量心
    // ------------------------------------------------------------------

    private static void renderMana(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        double maxMana = MagicClientData.maxMana();
        if (maxMana <= 0.0D) {
            return;
        }
        double mana = MagicClientData.mana();
        int hearts = Math.max(1, (int) Math.ceil(maxMana / 2.0D));

        int rightX = graphics.guiWidth() / 2 + 91;   // 与原版饱食度右边缘对齐
        int baseY = graphics.guiHeight() - 49;       // 饱食度正上方一行

        for (int i = 0; i < hearts; i++) {
            int row = i / HEARTS_PER_ROW;
            int col = i % HEARTS_PER_ROW;
            int x = rightX - (col + 1) * HEART_SIZE;
            int y = baseY - row * (HEART_SIZE + 1);

            double value = mana - i * 2.0D;
            if (value >= 2.0D) {
                drawHeart(graphics, x, y, MANA_FULL, MANA_FULL_LIGHT, HEART.length, Integer.MAX_VALUE);
            } else if (value >= 1.0D) {
                drawHeart(graphics, x, y, MANA_FULL, MANA_FULL, HEART.length, 4);
            } else {
                drawHeart(graphics, x, y, MANA_EMPTY, MANA_EMPTY, HEART.length, Integer.MAX_VALUE);
            }
        }

        // 一排以内时在旁边标个数字，超过一排就不画，避免遮挡
        if (hearts <= HEARTS_PER_ROW) {
            String text = String.format("%.0f/%.0f", mana, maxMana);
            int textX = rightX - HEARTS_PER_ROW * HEART_SIZE - 4 - minecraft.font.width(text);
            graphics.text(minecraft.font, Component.literal(text), textX, baseY + 1, 0xFF9FC4FF);
        }
    }

    /**
     * 画一颗心。
     *
     * @param fillColor  主色
     * @param lightColor 左上高光色
     * @param rows       画多少行（半颗心时只画上半部分）
     * @param maxCol     只画到第几列（半颗心用 4）
     */
    private static void drawHeart(GuiGraphicsExtractor graphics, int x, int y,
                                  int fillColor, int lightColor, int rows, int maxCol) {
        for (int row = 0; row < rows && row < HEART.length; row++) {
            String line = HEART[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean inside = col < line.length() && col < maxCol && line.charAt(col) == '#';
                if (inside && start < 0) {
                    start = col;
                } else if (!inside && start >= 0) {
                    // 顶部两行用高光色，看起来有立体感
                    int color = row <= 1 ? lightColor : fillColor;
                    graphics.fill(x + start, y + row, x + col, y + row + 1, color);
                    start = -1;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 技能栏 Z X C V（左下角文字形式）
    // ------------------------------------------------------------------

    private static void renderSkillBar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        if (MagicClientData.awakened().isEmpty()) {
            return;
        }

        // 4 行文字堆在左下角：槽位 1（默认 Z）在最上面，槽位 4（默认 V）贴着屏幕底
        for (int slot = 0; slot < 4; slot++) {
            int y = graphics.guiHeight() - SLOT_TEXT_BOTTOM - SLOT_LINE_HEIGHT * (4 - slot);
            graphics.text(minecraft.font, slotLine(slot), SLOT_TEXT_LEFT, y, 0xFFFFFFFF, true);
        }
    }

    /**
     * 拼一行槽位文字：{@code Z键: 蟒痕 3.2s}
     * <ul>
     *   <li>按键名实时取按键绑定（玩家改键后自动跟着变）；</li>
     *   <li>未觉醒系列或没学技能显示灰色“无技能”；</li>
     *   <li>技能名用系列代表色；</li>
     *   <li>只在冷却中才追加黄色剩余秒数。</li>
     * </ul>
     */
    private static Component slotLine(int slot) {
        MutableComponent line = Component.literal(slotKeyName(slot) + "键: ");

        MagicElement element = MagicClientData.elementAt(slot);
        SkillDefinition skill = element == null ? null : MagicClientData.selectedSkill(element);
        if (element == null || skill == null) {
            return line.append(Component.literal("无技能").withStyle(ChatFormatting.GRAY));
        }

        line.append(Component.literal(skill.name()).withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(element.color()))));

        double cooldown = MagicClientData.cooldownSeconds(skill.id());
        if (cooldown > 0.0D) {
            line.append(Component.literal(String.format(" %.1fs", cooldown)).withStyle(ChatFormatting.YELLOW));
        }
        return line;
    }

    /** 槽位当前绑定的按键显示名（玩家改键后跟着变），兜底显示默认字母 */
    private static String slotKeyName(int slot) {
        KeyMapping mapping = MagicKeybinds.SLOTS[slot];
        if (mapping == null) {
            return new String[]{"Z", "X", "C", "V"}[slot];
        }
        String name = mapping.getTranslatedKeyMessage().getString();
        return name.isEmpty() ? "?" : name;
    }
}
