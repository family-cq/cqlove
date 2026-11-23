package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：按下 Z/X/C/V 释放对应槽位的魔法系列技能。
 *
 * <p>只传槽位（0=Z 1=X 2=C 3=V），具体是哪个系列、哪个技能由服务端根据玩家自己的数据决定，
 * 避免客户端伪造技能。</p>
 *
 * @param slot 按键槽位 0-3
 * @param ctrl 按下时是否按住 Ctrl（部分技能用它区分“对自己 / 对目标”释放，如土系石盾）
 */
public record MagicCastPayload(int slot, boolean ctrl) implements CustomPacketPayload {

    /** 包标识：cqlove:magic_cast */
    public static final Type<MagicCastPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "magic_cast"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicCastPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MagicCastPayload::slot,
                    ByteBufCodecs.BOOL, MagicCastPayload::ctrl,
                    MagicCastPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
