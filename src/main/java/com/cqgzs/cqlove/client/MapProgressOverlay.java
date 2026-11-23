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

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.network.MapProgressPayload;

/**
 * 区块预加载进度条（需求文档 二.3.1 “主界面再增加一个百分比进度条”）。
 *
 * <p>绘制在屏幕顶部居中：一行文字 + 一条 182x5 的进度条，完成后 2 秒淡出。</p>
 *
 * <p>26.1.2 的 HUD 通过 {@link RegisterGuiLayersEvent} 注册 {@code GuiLayer} 实现，
 * 这里注册在“所有原版图层之上”，保证不被其他 HUD 遮挡。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID, value = Dist.CLIENT)
public final class MapProgressOverlay {

    /** 图层标识：cqlove:map_progress */
    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath("cqlove", "map_progress");

    /** 进度条尺寸 */
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;

    /** 配色 */
    private static final int BAR_BG = 0xFF2B2B2B;
    private static final int BAR_BORDER = 0xFF000000;
    private static final int BAR_FILL = 0xFF3FC8FF;
    private static final int BAR_FILL_WRITING = 0xFFFFB03A;
    private static final int BAR_FILL_DONE = 0xFF4CE04C;

    private MapProgressOverlay() {
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER_ID, MapProgressOverlay::render);
    }

    /** 图层渲染入口 */
    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!ClientMapState.isVisible()) {
            return;
        }
        MapProgressPayload data = ClientMapState.get();
        if (data == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // 打开箱子等界面时隐藏，避免遮挡
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }

        // 两个阶段：生成区块 -> 写入地图数据
        boolean writing = ClientMapState.isWriting();
        int percent = writing ? ClientMapState.writePercent() : (data.finished() ? 100 : data.percent());
        int guiWidth = graphics.guiWidth();
        int left = (guiWidth - BAR_WIDTH) / 2;
        int top = 30;

        // 文字：地图预加载 45% (198/441) 半径 4 / 地图写入中 62%
        Component text = writing
                ? Component.literal(String.format("地图数据写入中 %d%%  (请稍候, 约需 %d 秒)",
                        percent, Math.max(1, (int) Math.round(data.total() * 1.2D / 60.0D))))
                : Component.literal(String.format(
                        "地图预加载 %d%%  (%d/%d)  半径 %d 区块",
                        percent, data.loaded(), data.total(), data.radius()));
        graphics.centeredText(minecraft.font, text, guiWidth / 2, top - 12, 0xFFFFFFFF);

        // 进度条：外框 + 底 + 填充
        graphics.fill(left - 1, top - 1, left + BAR_WIDTH + 1, top + BAR_HEIGHT + 1, BAR_BORDER);
        graphics.fill(left, top, left + BAR_WIDTH, top + BAR_HEIGHT, BAR_BG);
        int fillWidth = BAR_WIDTH * percent / 100;
        if (fillWidth > 0) {
            graphics.fill(left, top, left + fillWidth, top + BAR_HEIGHT,
                    writing ? BAR_FILL_WRITING : (data.finished() ? BAR_FILL_DONE : BAR_FILL));
        }
    }
}
