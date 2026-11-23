package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端：一条提示消息，客户端画在主界面中下位置的 HUD 上，不进聊天框。
 *
 * <p>替代原先的 {@code player.sendSystemMessage(...)}。服务端用
 * {@link com.cqgzs.cqlove.magic.NoticeApi#send(net.minecraft.server.level.ServerPlayer, Component)}
 * 发送，客户端由 {@link com.cqgzs.cqlove.client.ClientNotices} 缓存并渲染。</p>
 */
public record ClientNoticePayload(Component text) implements CustomPacketPayload {

    public static final Type<ClientNoticePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "client_notice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientNoticePayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> ComponentSerialization.STREAM_CODEC.encode(buf, payload.text()),
                    buf -> new ClientNoticePayload(ComponentSerialization.STREAM_CODEC.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
