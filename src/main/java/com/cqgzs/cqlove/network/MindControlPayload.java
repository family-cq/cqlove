package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：心灵系高阶「摄魄控心」按住 Alt 时的鼠标操作。
 *
 * <p>只传动作，不传坐标/实体：服务端按玩家自己的视线与施法范围重新计算目标，
 * 客户端无法伪造"隔墙瞬移"或"指定任意实体"。</p>
 *
 * @param action 1 = 右键移动（{@code MindMagic.CONTROL_MOVE}），
 *               2 = 左键攻击（{@code MindMagic.CONTROL_ATTACK}）
 */
public record MindControlPayload(int action) implements CustomPacketPayload {

    /** 包标识：cqlove:mind_control */
    public static final Type<MindControlPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "mind_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MindControlPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MindControlPayload::action,
                    MindControlPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
