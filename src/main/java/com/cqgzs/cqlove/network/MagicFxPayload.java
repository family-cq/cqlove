package com.cqgzs.cqlove.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 所有能看到该玩家的客户端：某个玩家的“跟随特效”开关位。
 *
 * <p>翅膀 / 脚下法阵已改为<b>客户端渲染层</b>（直接画在玩家模型上，像装备一样贴身，
 * 不再有实体追位置的延迟）。客户端渲染时需要知道“这个玩家该不该显示翅膀/法阵”，
 * 这个包就是广播这些状态的。位定义见客户端 {@code MagicClientFx}：</p>
 * <ul>
 *   <li>bit0 = 已学风之翼（背后六翼）</li>
 *   <li>bit1 = 火神焚天 buff（脚下火环）</li>
 *   <li>bit2 = 雷神降临 buff（脚下雷环，优先于火环）</li>
 * </ul>
 *
 * @param player 产生特效的玩家 UUID
 * @param flags  开关位，0 = 什么都没有
 */
public record MagicFxPayload(UUID player, int flags) implements CustomPacketPayload {

    /** 包标识：cqlove:magic_fx */
    public static final Type<MagicFxPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "magic_fx"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicFxPayload> CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, MagicFxPayload::player,
                    ByteBufCodecs.VAR_INT, MagicFxPayload::flags,
                    MagicFxPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
