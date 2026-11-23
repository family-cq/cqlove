package com.cqgzs.cqlove.client;

import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ModListScreen;

/**
 * 把原版标题界面的所有控件清空，只重建五个像素风按钮：
 * 单人游戏 / 多人游戏 / 选项 / 模组 / 退出游戏。
 */
public final class TitleMenuCustomizer {

    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int BUTTON_COUNT = 5;

    private TitleMenuCustomizer() {
    }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        // 原版控件（含语言、无障碍、Realms、版权按钮、开发者测试世界按钮）全部移除
        for (GuiEventListener listener : new ArrayList<>(event.getListenersList())) {
            event.removeListener(listener);
        }

        Minecraft minecraft = Minecraft.getInstance();
        int totalHeight = BUTTON_COUNT * BUTTON_HEIGHT + (BUTTON_COUNT - 1) * GAP;
        int x = (titleScreen.width - BUTTON_WIDTH) / 2;
        int y = (titleScreen.height - totalHeight) / 2 + 20;

        event.addListener(new PixelMenuButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("menu.singleplayer"),
                button -> minecraft.setScreen(new SelectWorldScreen(titleScreen))));
        y += BUTTON_HEIGHT + GAP;

        event.addListener(new PixelMenuButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("menu.multiplayer"),
                button -> minecraft.setScreen(defaultMultiplayerScreen(titleScreen))));
        y += BUTTON_HEIGHT + GAP;

        event.addListener(new PixelMenuButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("menu.options"),
                button -> minecraft.setScreen(new OptionsScreen(titleScreen, minecraft.options, false))));
        y += BUTTON_HEIGHT + GAP;

        event.addListener(new PixelMenuButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("fml.menu.mods"),
                button -> minecraft.setScreen(new ModListScreen(titleScreen))));
        y += BUTTON_HEIGHT + GAP;

        event.addListener(new PixelMenuButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("menu.quit"),
                button -> minecraft.stop()));
    }

    /** 与原版一致：首次进多人游戏先弹安全提示，之后直接进服务器列表。 */
    private static Screen defaultMultiplayerScreen(TitleScreen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(parent) : new SafetyScreen(parent);
    }
}
