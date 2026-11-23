package com.cqgzs.cqlove.client;

import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.config.CqloveConfig;
import com.cqgzs.cqlove.lan.LanShareScreen;

/**
 * 联机功能入口（需求文档 二.2）：把原版“对局域网开放”界面
 * 整体替换为我们的自定义界面 {@link LanShareScreen}。
 *
 * <p>实现方式：监听 NeoForge 的 {@link ScreenEvent.Opening}，
 * 检测到即将打开原版 {@link ShareToLanScreen} 时换成自定义界面，
 * 不需要 Mixin，稳定性最好。</p>
 *
 * <p>受配置框架 {@link CqloveConfig#lanEnabled()} 控制，关闭后不干预原版界面。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID, value = Dist.CLIENT)
public final class LanScreenHook {

    private LanScreenHook() {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        // 配置关闭联机功能时不动原版界面
        if (!CqloveConfig.lanEnabled()) {
            return;
        }
        // 只替换“对局域网开放”界面；注意不能用 getScreen()，它是替换前的旧值
        if (event.getNewScreen() instanceof ShareToLanScreen) {
            // 当前界面（暂停菜单）作为返回界面传入
            event.setNewScreen(new LanShareScreen(event.getCurrentScreen()));
        }
    }
}
