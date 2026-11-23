package com.cqgzs.cqlove.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.RenderPipelines;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.magic.MagicRank;
import com.cqgzs.cqlove.magic.MagicTalent;
import com.cqgzs.cqlove.magic.SkillDefinition;
import com.cqgzs.cqlove.magic.SkillRegistry;
import com.cqgzs.cqlove.network.MagicSelectPayload;
import com.cqgzs.cqlove.network.MagicTalentPayload;

/**
 * 魔法世界天赋界面（需求 5.1：按 P 打开，背景为像素画，两个页签“技能 / 天赋”）。
 *
 * <p>技能页：左侧 18 个系列图标（已觉醒高亮），点选后右侧显示该系列 4 阶技能树；
 * 已学技能可点击切换为当前技能（与 G 轮盘等效），未学/阶级不足会置灰并给出提示。</p>
 *
 * <p>天赋页：显示可用天赋点与 10 项天赋，点 “+1” 消耗 1 点（服务端校验后再同步回来）。</p>
 *
 * <p>界面开放时游戏不暂停（isPauseScreen = false），但不响应玩家移动与施法键。</p>
 */
public final class MagicTalentScreen extends Screen {

    private static final Identifier BACKGROUND =
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/gui/magic/talent_bg.png");

    // ---- 布局常量（GUI 坐标） ----
    private static final int TAB_W = 56;
    private static final int TAB_H = 16;
    private static final int TAB_Y = 26;
    private static final int TAB_SKILLS_X = 12;
    private static final int TAB_TALENTS_X = 12 + TAB_W + 4;

    private static final int GRID_X = 12;
    private static final int GRID_Y = 50;
    private static final int GRID_CELL = 18;
    private static final int GRID_COLS = 3;
    /** 系列图标框尺寸（边框紧贴图标，图标已是透明底无内置边框） */
    private static final int CELL_SIZE = 16;

    private static final int PANEL_X = 92;
    private static final int SLOT_SIZE = 16;
    private static final int SLOT_GAP = 70;

    private static final int TALENT_X = 12;
    private static final int TALENT_Y = 66;
    private static final int TALENT_ROW_H = 18;

    /** 当前页签 */
    private enum Tab { SKILLS, TALENTS }

    private Tab tab = Tab.SKILLS;
    /** 技能页当前查看的系列（默认第一个已觉醒系列） */
    private MagicElement element;
    /** 天赋加点的节流：同一 tick 内多次点击只发一次（避免连点刷包） */
    private long lastTalentClick;

    public MagicTalentScreen() {
        super(Component.literal("魔法世界"));
        List<MagicElement> awakened = MagicClientData.awakened();
        this.element = awakened.isEmpty() ? MagicElement.THUNDER : awakened.get(0);
    }

    /** 服务端让客户端打开本界面（觉醒书使用后调用），固定落在技能页 */
    public static void openFromServer() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new MagicTalentScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        // 背景图铺满 + 压暗，保证文字可读
        graphics.blit(BACKGROUND, 0, 0, width, height, 0.0F, 1.0F, 0.0F, 1.0F);
        graphics.fill(0, 0, width, height, 0xB8100E1A);
        // 顶栏
        graphics.fill(0, 0, width, 22, 0xCC2A1638);
        graphics.text(this.minecraft.font, Component.literal("魔法世界 · 天赋界面"), width / 2 - 50, 7, 0xFFE8D8FF, true);

        drawTabs(graphics, mouseX, mouseY);
        drawPlayerInfo(graphics, width);

        if (tab == Tab.SKILLS) {
            drawSkillsTab(graphics, mouseX, mouseY);
        } else {
            drawTalentsTab(graphics, mouseX, mouseY);
        }

        // 底部操作提示
        graphics.text(this.minecraft.font,
                Component.literal("P/ESC 关闭    双击未觉醒系列 = 觉醒（消耗觉醒书）    点击已学技能 = 切换当前技能").withStyle(ChatFormatting.GRAY),
                12, height - 14, 0xFFAAAAAA, true);
    }

    private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        drawTab(graphics, "技能", TAB_SKILLS_X, tab == Tab.SKILLS, mouseX, mouseY);
        drawTab(graphics, "天赋", TAB_TALENTS_X, tab == Tab.TALENTS, mouseX, mouseY);
    }

    private void drawTab(GuiGraphicsExtractor graphics, String name, int x, boolean active, int mouseX, int mouseY) {
        boolean hover = isInside(mouseX, mouseY, x, TAB_Y, TAB_W, TAB_H);
        int bg = active ? 0xFF6A3FB0 : (hover ? 0xFF3A2A55 : 0xFF241A33);
        graphics.fill(x, TAB_Y, x + TAB_W, TAB_Y + TAB_H, bg);
        graphics.outline(x, TAB_Y, TAB_W, TAB_H, active ? 0xFFD9B8FF : 0xFF6A5A85);
        graphics.centeredText(this.minecraft.font, Component.literal(name),
                x + TAB_W / 2, TAB_Y + 4, active ? 0xFFFFFFFF : 0xFFBFAFD0);
    }

    /** 右上角：阶级 / 蓝量 / 魔力 / 天赋点 */
    private void drawPlayerInfo(GuiGraphicsExtractor graphics, int width) {
        MagicRank rank = MagicClientData.rank();
        String text = String.format("%s  蓝量 %.0f/%.0f  天赋点 %d",
                rank.display(), MagicClientData.mana(), MagicClientData.maxMana(), MagicClientData.talentPoints());
        int textWidth = this.minecraft.font.width(text);
        graphics.text(this.minecraft.font, Component.literal(text), width - textWidth - 10, 7, 0xFFCFC0E8, true);
    }

    // ------------------------------------------------------------------
    // 技能页
    // ------------------------------------------------------------------

    private void drawSkillsTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        MagicElement[] elements = MagicElement.values();
        List<MagicElement> awakened = MagicClientData.awakened();

        // 左侧 18 个系列图标
        for (int i = 0; i < elements.length; i++) {
            MagicElement candidate = elements[i];
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int x = GRID_X + col * GRID_CELL;
            int y = GRID_Y + row * GRID_CELL;
            boolean isAwakened = awakened.contains(candidate);
            boolean selected = candidate == element;

            graphics.fill(x, y, x + CELL_SIZE, y + CELL_SIZE, isAwakened ? 0xFF2A1E3C : 0xFF18121F);
            blitElementIcon(graphics, candidate, x, y, CELL_SIZE);
            if (!isAwakened) {
                // 未觉醒压暗
                graphics.fill(x, y, x + CELL_SIZE, y + CELL_SIZE, 0xB0000000);
            }
            graphics.outline(x, y, CELL_SIZE, CELL_SIZE,
                    selected ? 0xFFFFFFFF : (isAwakened ? candidate.color() : 0xFF44404E));

            if (isInside(mouseX, mouseY, x, y, CELL_SIZE, CELL_SIZE)) {
                graphics.setTooltipForNextFrame(Component.literal(
                        candidate.display() + (isAwakened ? "（已觉醒）"
                                : "（未觉醒）\n双击觉醒此系列（消耗 1 本觉醒书，默认学会第一个初阶技能）")), mouseX, mouseY);
            }
        }

        // 右侧技能树
        int y = GRID_Y;
        boolean isAwakened = awakened.contains(element);
        graphics.text(this.minecraft.font, Component.literal(element.display()
                        + (isAwakened ? "" : "  ·  未觉醒（双击左侧系列图标觉醒，消耗 1 本觉醒书）"))
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(element.color()))),
                PANEL_X, y, 0xFFFFFFFF, true);
        y += 14;

        int rankIndex = MagicClientData.rank().ordinal();
        for (int tierIndex = 0; tierIndex < 4; tierIndex++) {
            List<SkillDefinition> skills = SkillRegistry.of(element, com.cqgzs.cqlove.magic.SkillTier.byOrdinal(tierIndex));
            String tierHeader = com.cqgzs.cqlove.magic.SkillTier.byOrdinal(tierIndex).display()
                    + " · " + (skills.isEmpty() ? "?" : skills.get(0).tierName());
            boolean tierLocked = tierIndex > rankIndex;
            graphics.text(this.minecraft.font, Component.literal(tierHeader),
                    PANEL_X, y, tierLocked ? 0xFF8A8496 : 0xFFE0D4F0, true);
            y += 11;

            for (int i = 0; i < skills.size(); i++) {
                SkillDefinition skill = skills.get(i);
                int x = PANEL_X + i * SLOT_GAP;
                boolean learned = MagicClientData.isLearned(skill.id());
                boolean locked = tierLocked;
                boolean isCurrent = learned && isCurrentSkill(skill);

                graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, learned ? 0xFF2A1E3C : 0xFF18121F);
                blitElementIcon(graphics, element, x, y, SLOT_SIZE);
                if (!learned) {
                    graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xB8000000);
                }
                graphics.outline(x, y, SLOT_SIZE, SLOT_SIZE,
                        isCurrent ? 0xFFFFE066 : (learned ? element.color() : 0xFF44404E));

                // 技能名（已学=系列色，未学=灰，锁定=暗灰）
                int nameColor = learned ? element.color() : (locked ? 0xFF6E6878 : 0xFF9A94A6);
                graphics.text(this.minecraft.font, Component.literal(skill.name()), x + SLOT_SIZE + 3, y + 2,
                        nameColor, true);

                if (isInside(mouseX, mouseY, x, y, SLOT_SIZE, SLOT_SIZE)
                        || isInside(mouseX, mouseY, x, y, SLOT_SIZE + 46, SLOT_SIZE)) {
                    graphics.setTooltipForNextFrame(skillTooltip(skill, learned, locked), mouseX, mouseY);
                }
            }
            y += SLOT_SIZE + 5;
            if (y > graphics.guiHeight() - 30) {
                break; // 小窗口下防止画出屏幕
            }
        }
    }

    /** 该技能是不是该系列当前选中的技能 */
    private boolean isCurrentSkill(SkillDefinition skill) {
        SkillDefinition current = MagicClientData.selectedSkill(skill.element());
        return current != null && current.id().equals(skill.id());
    }

    private Component skillTooltip(SkillDefinition skill, boolean learned, boolean locked) {
        StringBuilder builder = new StringBuilder(skill.fullName());
        if (locked) {
            builder.append("\n阶级不足，无法学习");
        } else if (!learned) {
            builder.append("\n未学习（使用天赋书学习）");
        } else {
            builder.append("\n已学会，点击可设为当前技能");
        }
        builder.append("\n耗蓝 ").append(skill.manaCost());
        builder.append("  冷却 ").append(skill.cooldownSeconds(MagicClientData.rank())).append(" 秒");
        builder.append("  范围 ").append((int) skill.range(com.cqgzs.cqlove.client.MagicClientData.rank())).append(" 格");
        builder.append("\n").append(skill.description());
        return Component.literal(builder.toString());
    }

    // ------------------------------------------------------------------
    // 天赋页
    // ------------------------------------------------------------------

    private void drawTalentsTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(this.minecraft.font,
                Component.literal("可用天赋点: " + MagicClientData.talentPoints()
                        + "    （每本体质书 +1 点，点击右侧 +1 加点）").withStyle(ChatFormatting.GOLD),
                TALENT_X, TALENT_Y - 16, 0xFFFFE066, true);

        MagicTalent[] talents = MagicTalent.values();
        int y = TALENT_Y;
        for (MagicTalent talent : talents) {
            int level = MagicClientData.talentLevel(talent);
            boolean maxed = level >= talent.maxLevel();
            boolean canAdd = !maxed && MagicClientData.talentPoints() > 0;

            graphics.text(this.minecraft.font, Component.literal(talent.display()), TALENT_X, y, 0xFFE8E0F4, true);
            graphics.text(this.minecraft.font, Component.literal(level + "/" + talent.maxLevel()),
                    TALENT_X + 62, y, maxed ? 0xFF7FE07F : 0xFFBFAFD0, true);
            String unit = isPercentTalent(talent) ? "%" : "";
            String info = "每点 +" + trimNumber(talent.gainPerPoint()) + unit;
            if (talent.baseValue() > 0.0D) {
                // 暴击/暴伤/魔暴击/魔暴伤/元素精通：显示初始属性与叠加天赋后的当前总值
                info += "    初始 " + trimNumber(talent.baseValue()) + unit
                        + "    当前 " + trimNumber(talent.totalAt(level)) + unit;
            } else {
                info += "    当前 +" + trimNumber(talent.valueAt(level)) + unit;
            }
            graphics.text(this.minecraft.font, Component.literal(info),
                    TALENT_X + 110, y, 0xFF9C94A8, true);

            // +1 按钮
            int bx = TALENT_X + 268;
            int by = y - 2;
            boolean hover = isInside(mouseX, mouseY, bx, by, 16, 14);
            graphics.fill(bx, by, bx + 16, by + 14,
                    canAdd ? (hover ? 0xFF5FA85F : 0xFF3F7F3F) : 0xFF2E2A36);
            graphics.outline(bx, by, 16, 14, canAdd ? 0xFF8FE08F : 0xFF4A4654);
            graphics.centeredText(this.minecraft.font, Component.literal("+1"), bx + 8, by + 3,
                    canAdd ? 0xFFFFFFFF : 0xFF7A7486);

            if (hover) {
                graphics.setTooltipForNextFrame(Component.literal(maxed ? "已满级"
                        : (MagicClientData.talentPoints() > 0 ? "点击加 1 点（消耗 1 天赋点）" : "没有可用天赋点（使用体质书获得）")),
                        mouseX, mouseY);
            }
            y += TALENT_ROW_H;
        }
    }

    /** 百分比类天赋（暴击/暴伤/魔暴击/魔暴伤）显示时带 % */
    private boolean isPercentTalent(MagicTalent talent) {
        return talent == MagicTalent.CRIT || talent == MagicTalent.CRIT_DAMAGE
                || talent == MagicTalent.MAGIC_CRIT || talent == MagicTalent.MAGIC_CRIT_DAMAGE;
    }

    private static String trimNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.1f", value);
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();

            // 页签
            if (isInside(mouseX, mouseY, TAB_SKILLS_X, TAB_Y, TAB_W, TAB_H)) {
                tab = Tab.SKILLS;
                return true;
            }
            if (isInside(mouseX, mouseY, TAB_TALENTS_X, TAB_Y, TAB_W, TAB_H)) {
                tab = Tab.TALENTS;
                return true;
            }

            if (tab == Tab.SKILLS && clickSkillsTab(mouseX, mouseY, doubleClick)) {
                return true;
            }
            if (tab == Tab.TALENTS && clickTalentsTab(mouseX, mouseY)) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickSkillsTab(int mouseX, int mouseY, boolean doubleClick) {
        // 左侧系列选择：单击查看技能树；双击未觉醒的系列 -> 请求觉醒（消耗觉醒书，服务端校验）
        MagicElement[] elements = MagicElement.values();
        for (int i = 0; i < elements.length; i++) {
            int x = GRID_X + (i % GRID_COLS) * GRID_CELL;
            int y = GRID_Y + (i / GRID_COLS) * GRID_CELL;
            if (isInside(mouseX, mouseY, x, y, CELL_SIZE, CELL_SIZE)) {
                MagicElement candidate = elements[i];
                if (doubleClick && !MagicClientData.awakened().contains(candidate)) {
                    // 双击觉醒：发请求给服务端，成功后才扣觉醒书（没觉醒成功书不减）
                    ClientPacketDistributor.sendToServer(
                            new com.cqgzs.cqlove.network.MagicAwakenPayload(candidate.id()));
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.playSound(
                                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, 0.6F, 1.2F);
                    }
                    return true;
                }
                element = candidate;
                return true;
            }
        }

        // 右侧技能：点已学技能 → 切换当前技能
        int y = GRID_Y + 14;
        int rankIndex = MagicClientData.rank().ordinal();
        for (int tierIndex = 0; tierIndex < 4; tierIndex++) {
            List<SkillDefinition> skills = SkillRegistry.of(element, com.cqgzs.cqlove.magic.SkillTier.byOrdinal(tierIndex));
            y += 11;
            for (int i = 0; i < skills.size(); i++) {
                SkillDefinition skill = skills.get(i);
                int x = PANEL_X + i * SLOT_GAP;
                if (isInside(mouseX, mouseY, x, y, SLOT_SIZE, SLOT_SIZE)
                        && tierIndex <= rankIndex && MagicClientData.isLearned(skill.id())) {
                    MagicClientData.applyLocalSelection(element, skill.id());
                    ClientPacketDistributor.sendToServer(new MagicSelectPayload(element.id(), skill.id()));
                    return true;
                }
            }
            y += SLOT_SIZE + 5;
        }
        return false;
    }

    private boolean clickTalentsTab(int mouseX, int mouseY) {
        MagicTalent[] talents = MagicTalent.values();
        for (int i = 0; i < talents.length; i++) {
            int bx = TALENT_X + 268;
            int by = TALENT_Y + i * TALENT_ROW_H - 2;
            if (isInside(mouseX, mouseY, bx, by, 16, 14)) {
                MagicTalent talent = talents[i];
                if (MagicClientData.talentPoints() <= 0
                        || MagicClientData.talentLevel(talent) >= talent.maxLevel()) {
                    return true;
                }
                // 100ms 节流，避免连点瞬间刷包（服务端仍会逐点校验）
                long now = System.currentTimeMillis();
                if (now - lastTalentClick < 100L) {
                    return true;
                }
                lastTalentClick = now;
                ClientPacketDistributor.sendToServer(new MagicTalentPayload(i));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 画系列图标（16x16，资源在 textures/gui/magic/element/<id>.png） */
    private void blitElementIcon(GuiGraphicsExtractor graphics, MagicElement candidate, int x, int y, int size) {
        Identifier icon = Identifier.fromNamespaceAndPath(PlatformMods.MODID,
                "textures/gui/magic/element/" + candidate.id() + ".png");
        graphics.blit(icon, x, y, x + size, y + size, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
