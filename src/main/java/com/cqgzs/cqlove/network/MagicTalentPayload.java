package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：P 天赋界面里给某项天赋加 1 点。
 *
 * <p>服务端重新校验：有可用天赋点、未达该天赋等级上限，防止客户端伪造。</p>
 *
 * @param talentOrdinal 天赋序号（{@code MagicTalent.values()} 下标）
 */
public record MagicTalentPayload(int talentOrdinal) implements CustomPacketPayload {

    /** 包标识：cqlove:magic_talent */
    public static final Type<MagicTalentPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "magic_talent"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicTalentPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MagicTalentPayload::talentOrdinal,
                    MagicTalentPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
