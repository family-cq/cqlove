package com.cqgzs.cqlove.client;

import com.cqgzs.cqlove.PlatformMods;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 标题界面用的像素风按钮：底图为 AI 生成的魔法石质面板（正常 / 悬停 / 禁用三态），
 * 文字使用粗体 + 阴影并放大 1.4 倍，保证在暗色石板上足够醒目。
 */
public class PixelMenuButton extends Button {

    private static final Identifier TEX_NORMAL = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/gui/title/cqlove_button.png");
    private static final Identifier TEX_HOVER = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/gui/title/cqlove_button_hover.png");
    private static final Identifier TEX_DISABLED = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/gui/title/cqlove_button_disabled.png");

    /**
     * 贴图原始尺寸，用于 blit 的 UV 换算。
     * <p>注意：此版本 {@code blit(pipe, tex, x, y, u, v, width, height, texW, texH)}
     * 会把 {@code width/height} 同时当作「目标尺寸」和「取样尺寸」，
     * 即 srcWidth = width。因此贴图尺寸必须和按钮尺寸一致（120x20），
     * 否则只会取到贴图左上角的一小块。</p>
     */
    private static final int TEX_WIDTH = 120;
    private static final int TEX_HEIGHT = 20;

    /** 按钮缩到 120x20 后，字号按 1:1 绘制（粗体 + 阴影保证可读性）。 */
    private static final float TEXT_SCALE = 1.0F;

    public PixelMenuButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = this.active && this.isHoveredOrFocused();
        Identifier texture = !this.active ? TEX_DISABLED : (highlighted ? TEX_HOVER : TEX_NORMAL);

        // 显式给出取样区域（srcWidth/srcHeight），使贴图始终整张铺满按钮，
        // 不会再随按钮尺寸变化而被裁掉一块。
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture,
                this.getX(), this.getY(), 0.0F, 0.0F,
                this.width, this.height,
                TEX_WIDTH, TEX_HEIGHT,
                TEX_WIDTH, TEX_HEIGHT);

        Font font = Minecraft.getInstance().font;
        Component label = this.getMessage().copy().withStyle(ChatFormatting.BOLD);
        int color = !this.active ? 0xFF8C8C8C : (highlighted ? 0xFFFFE58A : 0xFFFFFFFF);

        int textWidth = font.width(label);
        float textX = this.getX() + (this.width - textWidth * TEXT_SCALE) / 2.0F;
        float textY = this.getY() + (this.height - 8.0F * TEXT_SCALE) / 2.0F + 1.0F;

        graphics.pose().pushMatrix();
        graphics.pose().translate(textX, textY);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE);
        graphics.text(font, label, 0, 0, color, true);
        graphics.pose().popMatrix();
    }
}
