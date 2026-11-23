package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：诅咒系超阶「拘灵遣将」按住 Alt 时的鼠标操作。
 *
 * <p>只传动作，不传坐标/实体：服务端按玩家自己的视线重新算目标，防伪造。</p>
 *
 * @param action 1 = 右键吸收（{@code CurseMagic.CONTROL_ABSORB}），
 *               2 = 左键放出 / 指定目标（{@code CurseMagic.CONTROL_RELEASE}）
 */
public record CurseControlPayload(int action) implements CustomPacketPayload {

    public static final Type<CurseControlPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "curse_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CurseControlPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CurseControlPayload::action,
                    CurseControlPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
