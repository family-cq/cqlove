package com.cqgzs.cqlove.lan;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 联机功能的服务端生命周期事件：世界/服务器关闭时复位运行时状态，
 * 避免上一局的 UUID 修复开关、玩家最大数、PVP 开关残留到下一局。
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class LanCommonEvents {

    private LanCommonEvents() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LanRuntime.reset();
        PvpApi.setPvpAllowed(false); // 需求文档：允许PVP 默认关闭，关服后复位默认
    }
}
