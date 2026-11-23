package com.cqgzs.cqlove.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.cqgzs.cqlove.map.MapPreloadService;

/**
 * 进度包 / 确认包的处理入口。
 *
 * <p>注意：{@code ClientMapState} 是纯客户端类，只有在确认运行在客户端（{@link FMLEnvironment#getDist()}）
 * 之后才在 lambda 内部引用，保证专用服务端不会因类加载而崩溃。</p>
 */
public final class MapProgressHandler {

    private MapProgressHandler() {
    }

    /** 客户端：服务端推进度（含“生成完成，请开始写地图”的通知） */
    public static void handle(MapProgressPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            // 切回客户端主线程再更新 UI 状态 + 调整视距
            context.enqueueWork(() -> com.cqgzs.cqlove.client.ClientMapState.onProgress(payload));
        }
    }

    /** 服务端：客户端回报“地图数据写完了”，可以恢复视距 */
    public static void handleAck(MapPreloadAckPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> MapPreloadService.onClientAck(player));
        }
    }
}
