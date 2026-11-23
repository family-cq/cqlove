package com.cqgzs.cqlove.command;

import com.cqgzs.cqlove.magic.NoticeApi;

import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.lan.PvpApi;

/**
 * PVP 开关指令：{@code /pvp on} 打开、{@code /pvp off} 关闭，{@code /pvp} 查询当前状态。
 *
 * <p>PVP 是联机功能（需求文档 二.2.h）而非魔法世界子系统，因此注册为独立顶层指令、
 * 不受"魔法世界"开关影响，玩家间伤害统一由 {@link PvpApi} 判定。</p>
 *
 * <p>切换后向全服广播状态，避免玩家不清楚当前能否互相攻击。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class PvpCommand {

    private PvpCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("pvp")
                // /pvp —— 查询当前状态
                .executes(PvpCommand::status)
                // /pvp on —— 打开 PVP
                .then(Commands.literal("on").executes(context -> set(context, true)))
                // /pvp off —— 关闭 PVP
                .then(Commands.literal("off").executes(context -> set(context, false))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        boolean on = PvpApi.isPvpAllowed();
        context.getSource().sendSuccess(() -> Component.literal("当前 PVP: " + (on ? "开启" : "关闭"))
                .withStyle(on ? ChatFormatting.RED : ChatFormatting.GREEN), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context, boolean allowed) {
        PvpApi.setPvpAllowed(allowed);
        // 状态没变也广播一次，确保操作者看到反馈
        broadcast(context, allowed);
        return 1;
    }

    /** 向所有在线玩家广播 PVP 状态（操作者也在内） */
    private static void broadcast(CommandContext<CommandSourceStack> context, boolean allowed) {
        Component message = Component.literal("[PVP] 玩家间对战已" + (allowed ? "开启" : "关闭"))
                .withStyle(allowed ? ChatFormatting.RED : ChatFormatting.GREEN);
        var server = context.getSource().getServer();
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            NoticeApi.send(player, message);
        }
    }
}
