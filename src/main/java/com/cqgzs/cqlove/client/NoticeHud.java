package com.cqgzs.cqlove.client;

import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 主界面中下位置的提示层：显示技能反馈、物品使用结果、警告等文字。
 *
 * <p>全部来自 {@link ClientNotices}（服务端 netty 包 / 客户端本地调用），不经过聊天框。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID, value = Dist.CLIENT)
public final class NoticeHud {

    private static final Identifier LAYER =
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "client_notices");

    /** 行高与行间距 */
    private static final int LINE_HEIGHT = 16;
    private static final int LINE_GAP = 2;
    /** 基准高度占屏幕的比例（0.68 ≈ 屏幕中下，热键栏上方） */
    private static final double BASE_Y_RATIO = 0.68D;
    /** 背景内边距 */
    private static final int PAD_X = 7;

    private NoticeHud() {
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER, NoticeHud::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        List<ClientNotices.Entry> entries = ClientNotices.visible();
        if (entries.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        int centerX = graphics.guiWidth() / 2;
        int baseY = (int) (graphics.guiHeight() * BASE_Y_RATIO);

        int count = entries.size();
        for (int i = 0; i < count; i++) {
            // 最新的一条贴在最下方（最靠近视线），更早的依次往上
            ClientNotices.Entry entry = entries.get(count - 1 - i);
            float ratio = ClientNotices.lifeRatio(entry, now);
            // 最后 20% 时间淡出，避免提示突然消失
            int alpha = (int) (215 * Math.min(1.0F, ratio * 5.0F));
            int y = baseY - i * (LINE_HEIGHT + LINE_GAP);
            int width = minecraft.font.width(entry.text());
            int x = centerX - width / 2;

            graphics.fill(x - PAD_X, y - 2, x + width + PAD_X, y + LINE_HEIGHT - 3,
                    (alpha << 24) | 0x0B1020);
            graphics.text(minecraft.font, entry.text(), x, y, (alpha << 24) | 0xFFFFFF, true);
        }
    }
}
