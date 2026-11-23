package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：把某个系列的当前技能切换成指定技能（G 轮盘 / P 界面点击已学技能）。
 *
 * <p>服务端必须重新校验：系列已觉醒、技能已学习且属于该系列，防止客户端伪造。</p>
 *
 * @param elementId 系列 id（如 thunder）
 * @param skillId   要选中的技能 id（如 thunder_0_2）
 */
public record MagicSelectPayload(String elementId, String skillId) implements CustomPacketPayload {

    /** 包标识：cqlove:magic_select */
    public static final Type<MagicSelectPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "magic_select"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicSelectPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, MagicSelectPayload::elementId,
                    ByteBufCodecs.STRING_UTF8, MagicSelectPayload::skillId,
                    MagicSelectPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
