package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：天赋界面技能页里<b>双击</b>某个未觉醒的系列，请求觉醒它。
 *
 * <p>服务端必须重新校验：背包里有觉醒书、该系列未觉醒、未超觉醒上限；
 * 通过后觉醒该系列、默认学会第一个初阶技能，并消耗 1 本觉醒书（防伪造）。</p>
 *
 * @param elementId 系列 id（如 fire / earth）
 */
public record MagicAwakenPayload(String elementId) implements CustomPacketPayload {

    /** 包标识：cqlove:magic_awaken */
    public static final Type<MagicAwakenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "magic_awaken"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicAwakenPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, MagicAwakenPayload::elementId,
                    MagicAwakenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
