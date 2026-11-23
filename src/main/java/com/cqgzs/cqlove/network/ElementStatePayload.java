package com.cqgzs.cqlove.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.core.UUIDUtil;

/**
 * 服务端 → 客户端：生物身上附着的元素状态同步。
 *
 * <p>用于 Jade tooltip 显示（客户端缓存每只实体的元素列表）与客户端特效。
 * 元素用 ordinal 传输（MagicElement 顺序固定，协议版本已包含）。</p>
 */
public record ElementStatePayload(UUID entity, List<Integer> elements, List<Integer> remainingTicks)
        implements CustomPacketPayload {

    public static final Type<ElementStatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "element_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ElementStatePayload> CODEC =
            StreamCodec.of(ElementStatePayload::encode, ElementStatePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ElementStatePayload payload) {
        UUIDUtil.STREAM_CODEC.encode(buf, payload.entity());
        buf.writeVarInt(payload.elements().size());
        for (int i = 0; i < payload.elements().size(); i++) {
            buf.writeVarInt(payload.elements().get(i));
            buf.writeVarInt(i < payload.remainingTicks().size() ? payload.remainingTicks().get(i) : 0);
        }
    }

    private static ElementStatePayload decode(RegistryFriendlyByteBuf buf) {
        UUID entity = UUIDUtil.STREAM_CODEC.decode(buf);
        int n = buf.readVarInt();
        List<Integer> elements = new ArrayList<>(n);
        List<Integer> ticks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            elements.add(buf.readVarInt());
            ticks.add(buf.readVarInt());
        }
        return new ElementStatePayload(entity, elements, ticks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
