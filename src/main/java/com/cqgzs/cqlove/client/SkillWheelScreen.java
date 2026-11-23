package com.cqgzs.cqlove.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.magic.SkillDefinition;
import com.cqgzs.cqlove.network.MagicSelectPayload;

/**
 * 技能轮盘（需求 5.10：长按 G 打开，松开 G 时才选择鼠标所在区域的技能）。
 *
 * <p>关键行为：</p>
 * <ul>
 *   <li>按住 G 期间轮盘一直显示，鼠标移到技能上只做高亮，<b>不会</b>立即选中；</li>
 *   <li>松开 G 的瞬间，取鼠标当前所在扇区作为选择结果，并同步给服务端；</li>
 *   <li>鼠标不在任何扇区（中心/圈外）时松开 = 取消，不改变当前技能；</li>
 *   <li>ESC 直接关闭 = 取消。</li>
 * </ul>
 *
 * <p>实现注意：打开界面时原版会把所有按键强制置为“松开”（{@code KeyMapping.setAll(false)}），
 * 所以不能用 {@code KeyMapping.isDown()} 判断 G 是否还按着，这里直接轮询 GLFW 的按键状态。</p>
 */
public final class SkillWheelScreen extends Screen {

    /** 圆环半径相对屏幕的比例参数 */
    private static final int RADIUS_MARGIN = 56;
    private static final int ICON_SIZE = 24;
    /** 图标外侧的框体边距 */
    private static final int BOX_PAD = 1;
    /** 判定扇区时鼠标离中心的合适距离范围（相对半径） */
    private static final double INNER_FACTOR = 0.30D;
    private static final double OUTER_FACTOR = 1.35D;

    /** 技能图标是否存在（技能 id -> 是否存在专属 16×16 图标），只查一次 */
    private static final Map<String, Boolean> ICON_CACHE = new HashMap<>();

    private final List<SkillDefinition> entries = wheelEntries();
    /** 本次轮盘展示的系列（null = 未选中任何系列，展示全部已学技能） */
    private final MagicElement wheelElement = MagicClientData.armedElement();
    private int hovered = -1;
    /** 已经在收尾（避免 render 每帧重复发包） */
    private boolean finishing;

    public SkillWheelScreen() {
        super(Component.literal("技能轮盘"));
    }

    /**
     * 轮盘内容：已用 ZXCV 选中过系列时，只列出该系列已学的技能（避免技能多了轮盘混乱）；
     * 还没选中过（或该系列没有已学技能）时，退回展示全部已学技能（按觉醒系列分组排列）。
     */
    private static List<SkillDefinition> wheelEntries() {
        MagicElement armed = MagicClientData.armedElement();
        if (armed != null) {
            List<SkillDefinition> skills = MagicClientData.learnedSkills(armed);
            if (!skills.isEmpty()) {
                return skills;
            }
        }
        return MagicClientData.allLearnedSkills();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 渲染 + 松开检测
    // ------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int cx = width / 2;
        int cy = height / 2;
        int radius = Math.max(60, Math.min(width, height) / 2 - RADIUS_MARGIN);

        // 全屏压暗，突出轮盘
        graphics.fill(0, 0, width, height, 0x88000000);
        // 中心底盘
        graphics.fill(cx - 46, cy - 46, cx + 46, cy + 46, 0xCC1A1226);
        graphics.outline(cx - 46, cy - 46, 92, 92, 0xFF6A3FB0);

        if (entries.isEmpty()) {
            graphics.centeredText(this.minecraft.font,
                    Component.literal("没有可用技能"), cx, cy - 10, 0xFFFF8080);
            graphics.centeredText(this.minecraft.font,
                    Component.literal("先觉醒系列并用天赋书学习技能"), cx, cy + 4, 0xFFBFB4CC);
        } else {
            hovered = hitTest(mouseX, mouseY, cx, cy, radius);
            drawSectors(graphics, cx, cy, radius);
            drawCenterInfo(graphics, cx, cy, width);
        }

        graphics.centeredText(this.minecraft.font,
                Component.literal("松开 " + wheelKeyName() + " 选择高亮技能    ESC 取消"),
                cx, height - 16, 0xFFBFAFD0);

        // 顶部标注当前轮盘只列哪个系列的技能
        if (wheelElement != null) {
            graphics.centeredText(this.minecraft.font,
                    Component.literal("当前系列：" + wheelElement.display() + "（按 ZXCV 换系列）"),
                    cx, 12, 0xFF000000 | wheelElement.color());
        }

        // 松开 G 才算选择（界面打开时 KeyMapping 状态被清空，这里直接查 GLFW）
        if (!finishing && !isWheelKeyDown()) {
            finishing = true;
            // 延迟到 tick 末尾执行，避免在渲染阶段直接切换界面
            this.minecraft.execute(this::finishAndClose);
        }
    }

    private void drawSectors(GuiGraphicsExtractor graphics, int cx, int cy, int radius) {
        int count = entries.size();
        double step = 360.0D / count;

        for (int i = 0; i < count; i++) {
            SkillDefinition skill = entries.get(i);
            MagicElement element = skill.element();
            double angle = Math.toRadians(-90.0D + i * step);
            int ix = (int) Math.round(cx + Math.cos(angle) * radius) - ICON_SIZE / 2;
            int iy = (int) Math.round(cy + Math.sin(angle) * radius) - ICON_SIZE / 2;
            boolean active = i == hovered;

            // 高亮：图标后面垫一个系列色圆底
            if (active) {
                graphics.fill(ix - BOX_PAD, iy - BOX_PAD, ix + ICON_SIZE + BOX_PAD, iy + ICON_SIZE + BOX_PAD,
                        (element.color() & 0x00FFFFFF) | 0x99000000);
                graphics.outline(ix - BOX_PAD, iy - BOX_PAD, ICON_SIZE + BOX_PAD * 2, ICON_SIZE + BOX_PAD * 2,
                        0xFFFFFFFF);
            } else {
                graphics.fill(ix - BOX_PAD, iy - BOX_PAD, ix + ICON_SIZE + BOX_PAD, iy + ICON_SIZE + BOX_PAD,
                        0x99000000);
                graphics.outline(ix - BOX_PAD, iy - BOX_PAD, ICON_SIZE + BOX_PAD * 2, ICON_SIZE + BOX_PAD * 2,
                        (element.color() & 0x00FFFFFF) | 0x66000000);
            }

            Identifier icon = skillIcon(skill);
            graphics.blit(icon, ix, iy, ix + ICON_SIZE, iy + ICON_SIZE, 0.0F, 1.0F, 0.0F, 1.0F);

            // 技能名：鼠标移到图标上才显示（需求：悬停图片上方显示技能名称）
            if (active) {
                graphics.centeredText(this.minecraft.font,
                        Component.literal(skill.name()).withStyle(ChatFormatting.WHITE),
                        ix + ICON_SIZE / 2, iy - 12, 0xFFFFFFFF);
            }

            // 已在该系列当前选中的技能上打勾
            if (isCurrent(skill)) {
                graphics.centeredText(this.minecraft.font, Component.literal("✔"), ix + ICON_SIZE / 2,
                        iy + ICON_SIZE + 3, 0xFF7FE07F);
            }
        }
    }

    /**
     * 技能图标：优先用 {@code textures/gui/magic/skill/<技能id>.png}（按技能描述单独绘制），
     * 没画过的技能退回系列图标 {@code textures/gui/magic/element/<系列id>.png}。
     *
     * <p>是否存在只查一次并缓存，避免每帧都翻资源包。</p>
     */
    private Identifier skillIcon(SkillDefinition skill) {
        Identifier icon = Identifier.fromNamespaceAndPath(PlatformMods.MODID,
                "textures/gui/magic/skill/" + skill.id() + ".png");
        Boolean exists = ICON_CACHE.get(skill.id());
        if (exists == null) {
            exists = this.minecraft != null
                    && this.minecraft.getResourceManager().getResource(icon).isPresent();
            ICON_CACHE.put(skill.id(), exists);
        }
        if (exists) {
            return icon;
        }
        return Identifier.fromNamespaceAndPath(PlatformMods.MODID,
                "textures/gui/magic/element/" + skill.element().id() + ".png");
    }

    private void drawCenterInfo(GuiGraphicsExtractor graphics, int cx, int cy, int width) {
        if (hovered < 0 || hovered >= entries.size()) {
            graphics.centeredText(this.minecraft.font, Component.literal("选择技能"), cx, cy - 12, 0xFFBFB4CC);
            graphics.centeredText(this.minecraft.font, Component.literal("鼠标移到技能上"), cx, cy + 2, 0xFF8A8496);
            return;
        }
        SkillDefinition skill = entries.get(hovered);
        graphics.centeredText(this.minecraft.font,
                Component.literal(skill.fullName()), cx, cy - 26,
                0xFF000000 | skill.element().color());
        graphics.centeredText(this.minecraft.font,
                Component.literal("耗蓝 " + skill.manaCost() + "   冷却 "
                        + skill.cooldownSeconds(MagicClientData.rank()) + "s   范围 "
                        + (int) skill.range(MagicClientData.rank()) + "格"), cx, cy - 12, 0xFFD8CFF0);

        // 描述按中心区宽度折行，最多 4 行
        List<net.minecraft.util.FormattedCharSequence> lines =
                this.minecraft.font.split(Component.literal(skill.description()), 170);
        int y = cy + 2;
        for (int i = 0; i < lines.size() && i < 4; i++) {
            graphics.centeredText(this.minecraft.font, lines.get(i), cx, y, 0xFFA9A2B8);
            y += 10;
        }
    }

    // ------------------------------------------------------------------
    // 命中判定 / 选择
    // ------------------------------------------------------------------

    /** 鼠标位置对应的扇区下标，不在环上返回 -1 */
    private int hitTest(int mouseX, int mouseY, int cx, int cy, int radius) {
        int count = entries.size();
        if (count == 0) {
            return -1;
        }
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < radius * INNER_FACTOR || dist > radius * OUTER_FACTOR) {
            return -1;
        }
        double step = 360.0D / count;
        // atan2 返回 -180..180，+90 后以正上方为 0
        double angle = (Math.toDegrees(Math.atan2(dy, dx)) + 90.0D + 720.0D) % 360.0D;
        int index = (int) Math.round(angle / step) % count;
        return index;
    }

    /** 松开 G：选中并关闭 */
    private void finishAndClose() {
        if (this.minecraft == null) {
            return;
        }
        if (hovered >= 0 && hovered < entries.size()) {
            SkillDefinition skill = entries.get(hovered);
            MagicClientData.applyLocalSelection(skill.element(), skill.id());
            ClientPacketDistributor.sendToServer(new MagicSelectPayload(skill.element().id(), skill.id()));
        }
        this.minecraft.setScreen(null);
    }

    private boolean isCurrent(SkillDefinition skill) {
        SkillDefinition current = MagicClientData.selectedSkill(skill.element());
        return current != null && current.id().equals(skill.id());
    }

    /** 右键/左键点击不选择，避免误触（需求：只能靠松开 G 选择） */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return true;
    }

    @Override
    public void onClose() {
        finishing = true;
        super.onClose();
    }

    // ------------------------------------------------------------------
    // 按键状态
    // ------------------------------------------------------------------

    private static String wheelKeyName() {
        KeyMapping mapping = MagicKeybinds.WHEEL;
        return mapping == null ? "G" : mapping.getTranslatedKeyMessage().getString();
    }

    /** 直接问 GLFW：轮盘键当前是否按着（绕过界面打开时被清空的 KeyMapping 状态） */
    private static boolean isWheelKeyDown() {
        KeyMapping mapping = MagicKeybinds.WHEEL;
        if (mapping == null) {
            return false;
        }
        long handle = Minecraft.getInstance().getWindow().handle();
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
