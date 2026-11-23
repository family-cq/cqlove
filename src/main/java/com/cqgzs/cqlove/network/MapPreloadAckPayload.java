package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：地图数据已写入完毕的确认包（无字段）。
 *
 * <p>为什么需要它：预加载期间服务端临时提升了视距，客户端才能收到远处的区块；
 * 但客户端还要把收到的区块交给 Xaero 世界地图写进地图文件（每帧只写 1 个区块，
 * 需要几十秒）。在客户端写完之前如果服务端先恢复视距，客户端会按小视距把远处的区块丢掉，
 * 地图就白写了。所以由客户端写完后再回一个确认包，服务端收到后才恢复视距。</p>
 */
public record MapPreloadAckPayload() implements CustomPacketPayload {

    /** 包的唯一标识：cqlove:map_preload_ack */
    public static final Type<MapPreloadAckPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "map_preload_ack"));

    /** 空包编解码器 */
    public static final StreamCodec<RegistryFriendlyByteBuf, MapPreloadAckPayload> CODEC =
            StreamCodec.unit(new MapPreloadAckPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
