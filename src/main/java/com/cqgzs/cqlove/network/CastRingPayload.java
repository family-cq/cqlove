package com.cqgzs.cqlove.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 所有能看到该玩家的客户端：某玩家脚下的<b>超阶技能圆环</b>状态。
 *
 * <p>圆环不再是固定 2 秒特效，而是跟着技能持续时间走：<b>技能还没进入冷却，圆环就不消失</b>。
 * 服务端每 tick 由 {@code MagicCastRingSync} 检查状态，状态变化（开启 / 结束）时发包，
 * 并定期补发当前剩余时间，让中途进服或轻微时间漂移的客户端也能对齐。</p>
 *
 * <p>和翅膀/法阵一样走客户端渲染层（{@code PlayerFxLayer}），不生成任何实体，
 * 所以玩家走动时圆环是插值后贴身渲染，不会拖尾。</p>
 *
 * @param player          施法者 UUID
 * @param elementOrdinal  系列序号（{@code MagicElement.ordinal()}，客户端据此取对应颜色的贴图）；
 *                        -1 表示<b>立刻清除</b>圆环
 * @param remainingTicks  圆环还剩多少 tick；0 或负数同样表示清除
 */
public record CastRingPayload(UUID player, int elementOrdinal, int remainingTicks)
        implements CustomPacketPayload {

    /** 包标识：cqlove:cast_ring */
    public static final Type<CastRingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "cast_ring"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CastRingPayload> CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, CastRingPayload::player,
                    ByteBufCodecs.VAR_INT, CastRingPayload::elementOrdinal,
                    ByteBufCodecs.VAR_INT, CastRingPayload::remainingTicks,
                    CastRingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
