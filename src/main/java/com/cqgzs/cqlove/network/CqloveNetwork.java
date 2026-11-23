package com.cqgzs.cqlove.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 网络包注册中心（mod 事件总线）。
 *
 * <p>后续魔法世界的施法、天赋同步等包也统一在这里注册，保持“一个入口”。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class CqloveNetwork {

    /** 网络协议版本号，包字段变更时递增，避免旧客户端解析错位 */
    private static final String PROTOCOL_VERSION = "17";

    private CqloveNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // 服务端 -> 客户端：区块预加载进度 / 生成完成通知
        registrar.playToClient(
                MapProgressPayload.TYPE,
                MapProgressPayload.CODEC,
                MapProgressHandler::handle);

        // 客户端 -> 服务端：地图数据写入完毕，可以恢复视距了
        registrar.playToServer(
                MapPreloadAckPayload.TYPE,
                MapPreloadAckPayload.CODEC,
                MapProgressHandler::handleAck);

        // 魔法世界：服务端 -> 客户端 玩家魔法数据快照
        registrar.playToClient(
                MagicDataPayload.TYPE,
                MagicDataPayload.CODEC,
                MagicNetworkHandler::handleData);

        // 魔法世界：服务端 -> 所有观察者 玩家跟随特效开关位（翅膀/法阵渲染层用）
        registrar.playToClient(
                MagicFxPayload.TYPE,
                MagicFxPayload.CODEC,
                MagicNetworkHandler::handleFx);

        // 魔法世界：服务端 -> 所有观察者 超阶施法圆环（脚下系列色法阵，客户端渲染层画）
        registrar.playToClient(
                CastRingPayload.TYPE,
                CastRingPayload.CODEC,
                MagicNetworkHandler::handleCastRing);

        // 魔法世界：服务端 -> 所有观察者 心灵系圆圈/圆环（客户端渲染层贴身绘制，跟随玩家）
        registrar.playToClient(
                MagicCirclePayload.TYPE,
                MagicCirclePayload.CODEC,
                MagicNetworkHandler::handleMagicCircle);

        // 元素系统：服务端 -> 所有观察者 生物身上附着的元素状态（Jade tooltip 显示用）
        registrar.playToClient(
                ElementStatePayload.TYPE,
                ElementStatePayload.CODEC,
                MagicNetworkHandler::handleElementState);

        // 服务端 -> 客户端：文字提示（画在主界面中下 HUD，不进聊天框）
        registrar.playToClient(
                ClientNoticePayload.TYPE,
                ClientNoticePayload.CODEC,
                MagicNetworkHandler::handleClientNotice);

        // 魔法世界：客户端 -> 服务端 按下 Z/X/C/V 的施法请求
        registrar.playToServer(
                MagicCastPayload.TYPE,
                MagicCastPayload.CODEC,
                MagicNetworkHandler::handleCast);

        // 魔法世界：客户端 -> 服务端 切换某系列的当前技能（G 轮盘 / P 界面）
        registrar.playToServer(
                MagicSelectPayload.TYPE,
                MagicSelectPayload.CODEC,
                MagicNetworkHandler::handleSelect);

        // 魔法世界：客户端 -> 服务端 天赋加 1 点（P 界面）
        registrar.playToServer(
                MagicTalentPayload.TYPE,
                MagicTalentPayload.CODEC,
                MagicNetworkHandler::handleTalent);

        // 魔法世界：服务端 -> 客户端 使用觉醒书后打开天赋界面技能页
        registrar.playToClient(
                OpenTalentScreenPayload.TYPE,
                OpenTalentScreenPayload.CODEC,
                MagicNetworkHandler::handleOpenTalentScreen);

        // 魔法世界：客户端 -> 服务端 天赋界面技能页双击系列请求觉醒（消耗觉醒书）
        registrar.playToServer(
                MagicAwakenPayload.TYPE,
                MagicAwakenPayload.CODEC,
                MagicNetworkHandler::handleAwaken);

        // 魔法世界：客户端 -> 服务端 摄魄控心按住 Alt 时的右键移动 / 左键攻击
        registrar.playToServer(
                MindControlPayload.TYPE,
                MindControlPayload.CODEC,
                MagicNetworkHandler::handleMindControl);

        // 魔法世界：客户端 -> 服务端 拘灵遣将按住 Alt 时的右键吸收 / 左键放出
        registrar.playToServer(
                CurseControlPayload.TYPE,
                CurseControlPayload.CODEC,
                MagicNetworkHandler::handleCurseControl);

        // 魔法世界：服务端 -> 客户端 虚拟特效实体（生成 / 变换 / 销毁）
        // 服务端不再生成 Display 实体，画面全部由客户端自己建实体渲染
        registrar.playToClient(
                VisualFxPayload.TYPE,
                VisualFxPayload.CODEC,
                MagicNetworkHandler::handleVisualFx);
    }
}
