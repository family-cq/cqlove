package com.cqgzs.cqlove;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(PlatformMods.MODID)
public class PlatformMods {
    public static final String MODID = "cqlove";
    public static final Logger LOGGER = LogUtils.getLogger();



    public PlatformMods(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // 注册 Mod 配置框架（需求文档 二.1：联机功能/地图功能/魔法世界 三个开关）
        // 配置文件：.minecraft/config/cqlove-common.toml
        com.cqgzs.cqlove.config.CqloveConfig.register(modContainer);

        // 魔法世界：玩家数据附件（每个玩家单独一份，随存档保存）
        com.cqgzs.cqlove.magic.MagicData.ATTACHMENTS.register(modEventBus);
        // 魔法世界：召唤生物属性附件（每只召唤生物一份 10 项属性）
        com.cqgzs.cqlove.magic.SummonStats.ATTACHMENTS.register(modEventBus);
        // 魔法世界：四本书 + 专属创造模式 Tab（书和其他物品都放这里，不再占用原版"原材料"页）
        com.cqgzs.cqlove.item.MagicItems.register(modEventBus);
        com.cqgzs.cqlove.item.MagicTab.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");
    }


    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }
}
