package com.cqgzs.cqlove.map;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.config.CqloveConfig;

/**
 * 地图指令（需求文档 二.3.1）：{@code /map come <num>}。
 *
 * <p>num 为区块半径，以玩家为圆心加载区块，主界面显示百分比进度条。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class MapPreloadCommand {

    private MapPreloadCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 配置关闭地图功能时不注册指令
        if (!CqloveConfig.mapEnabled()) {
            return;
        }
        event.getDispatcher().register(
                Commands.literal("map")
                        .then(Commands.literal("come")
                                .then(Commands.argument("num", IntegerArgumentType.integer(1, MapPreloadService.MAX_RADIUS))
                                        .executes(ctx -> execute(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "num"))))));
    }

    private static int execute(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        // 仅玩家可执行（需要以玩家为圆心）
        if (player == null) {
            source.sendFailure(Component.literal("该指令只能由玩家执行"));
            return 0;
        }
        MapPreloadService.start(player, radius);
        return 1;
    }
}
