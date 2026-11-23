package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 区块预加载进度包（需求文档 二.3.1）。
 *
 * <p>服务端每次推进进度后发送给发起指令的玩家，客户端据此绘制主界面进度条。</p>
 *
 * @param radius   本次预加载的区块半径（以玩家为圆心的区块半径）
 * @param total    需要加载的区块总数
 * @param loaded   已加载区块数
 * @param finished 是否已完成（true 表示本次任务结束，客户端开始淡出进度条）
 */
public record MapProgressPayload(int radius, int total, int loaded, boolean finished)
        implements CustomPacketPayload {

    /** 包的唯一标识：cqlove:map_progress */
    public static final Type<MapProgressPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "map_progress"));

    /** 编解码器：4 个字段按顺序读写 */
    public static final StreamCodec<RegistryFriendlyByteBuf, MapProgressPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MapProgressPayload::radius,
                    ByteBufCodecs.VAR_INT, MapProgressPayload::total,
                    ByteBufCodecs.VAR_INT, MapProgressPayload::loaded,
                    ByteBufCodecs.BOOL, MapProgressPayload::finished,
                    MapProgressPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 已加载百分比（0-100，向下取整） */
    public int percent() {
        if (total <= 0) {
            return finished ? 100 : 0;
        }
        return (int) (loaded * 100L / total);
    }
}
