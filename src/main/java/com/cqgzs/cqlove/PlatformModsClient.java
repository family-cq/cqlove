package com.cqgzs.cqlove;

import com.cqgzs.cqlove.client.TitleMenuCustomizer;
import com.cqgzs.cqlove.client.WindowIconSetter;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = PlatformMods.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = PlatformMods.MODID, value = Dist.CLIENT)
public class PlatformModsClient {
    public PlatformModsClient(ModContainer container) {



        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(TitleMenuCustomizer::onScreenInit);
        // 客户端每渲染帧驱动一次：Xaero 是每帧写 1 个区块，按帧计数才能准确判断“地图写完了”
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) ->
                com.cqgzs.cqlove.client.ClientMapState.onFrame());
        // 退出世界时把临时提升的视距还原，避免影响玩家后续游戏
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
            com.cqgzs.cqlove.client.ClientMapState.reset();
            com.cqgzs.cqlove.client.MagicClientData.clear();
            com.cqgzs.cqlove.client.MagicClientFx.clear();
            // 虚拟特效实体是本客户端自己建的，退出世界必须整体收掉
            com.cqgzs.cqlove.client.ClientVisualFx.clear();
        });
        // 虚拟特效实体：每 tick 清理过期项（服务端 remove 包丢失 / 中途退服的兜底）
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                com.cqgzs.cqlove.client.ClientVisualFx.tick());
        // 魔法世界：每 tick 处理 Z/X/C/V 技能按键与冷却倒计时
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                com.cqgzs.cqlove.client.MagicKeybinds.onClientTick());
        // 摄魄控心：Alt 状态 + 鼠标「按下沿」。单次快速点击也会漏掉 tick 轮询，必须走输入事件
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.Key e) ->
                com.cqgzs.cqlove.client.MindControlInput.onKey(e));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre e) ->
                com.cqgzs.cqlove.client.MindControlInput.onMouseButton(e));
        // 拘灵遣将：Alt + 右键吸收 / 左键放出（与摄魄控心同一套输入事件，服务端按各自的技能状态分流）
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.Key e) ->
                com.cqgzs.cqlove.client.CurseControlInput.onKey(e));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre e) ->
                com.cqgzs.cqlove.client.CurseControlInput.onMouseButton(e));
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        PlatformMods.LOGGER.info("HELLO FROM CLIENT SETUP");
        PlatformMods.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        // 窗口/任务栏图标必须在窗口创建后、主线程上设置
        event.enqueueWork(WindowIconSetter::apply);
    }

    /**
     * 给所有玩家皮肤（宽臂/细臂）挂上魔法跟随特效层：
     * 翅膀和法阵直接画在玩家模型上（像披风一样贴身），不再有实体追位置的延迟。
     */
    @SubscribeEvent
    static void onAddLayers(net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers event) {
        for (net.minecraft.world.entity.player.PlayerModelType skin : event.getSkins()) {
            net.minecraft.client.renderer.entity.player.AvatarRenderer<net.minecraft.client.player.AbstractClientPlayer> renderer =
                    event.getPlayerRenderer(skin);
            if (renderer != null) {
                renderer.addLayer(new com.cqgzs.cqlove.client.PlayerFxLayer(renderer));
            }
        }
    }

    /**
     * 渲染状态扩展：把每个玩家的特效开关位（MagicClientFx 缓存）与超阶施法圆环写进 AvatarRenderState，
     * PlayerFxLayer 渲染时直接读，不需要在渲染线程回查实体。
     */
    @SubscribeEvent
    static void onRegisterRenderStateModifiers(
            net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier() {
            @Override
            public <T extends net.minecraft.world.entity.Avatar & net.minecraft.client.entity.ClientAvatarEntity>
            void accept(T avatar, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
                state.setRenderData(com.cqgzs.cqlove.client.MagicClientFx.FLAGS_KEY,
                        com.cqgzs.cqlove.client.MagicClientFx.flagsOf(avatar.getUUID()));
                // 圆环跟着超阶技能持续时间走：没有/已过期时传 null，setRenderData 会把键移除（避免渲染状态复用留脏数据）
                state.setRenderData(com.cqgzs.cqlove.client.MagicClientFx.CAST_RING_KEY,
                        com.cqgzs.cqlove.client.MagicClientFx.castRingOf(avatar.getUUID()));
                // 心灵系圆圈：同样贴身绘制，按样式分别缓存，客户端本地倒计时
                state.setRenderData(com.cqgzs.cqlove.client.MagicClientFx.CIRCLES_KEY,
                        com.cqgzs.cqlove.client.MagicClientFx.circlesOf(avatar.getUUID()));
            }
        });
    }
}
