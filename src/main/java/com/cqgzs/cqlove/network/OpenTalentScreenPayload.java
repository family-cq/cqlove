package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 客户端：使用觉醒书后，打开天赋界面的技能页（双击未觉醒系列进行觉醒）。
 *
 * <p>觉醒书在使用时不消耗；玩家在界面里双击某个未觉醒系列、服务端校验通过后才扣 1 本。</p>
 */
public record OpenTalentScreenPayload() implements CustomPacketPayload {

    /** 包标识：cqlove:open_talent_screen */
    public static final Type<OpenTalentScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "open_talent_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTalentScreenPayload> CODEC =
            StreamCodec.unit(new OpenTalentScreenPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
