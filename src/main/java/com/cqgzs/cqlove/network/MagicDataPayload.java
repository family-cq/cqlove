package com.cqgzs.cqlove.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 客户端：玩家魔法数据快照。
 *
 * <p>每次数据变化（觉醒/学技能/放技能扣蓝/冷却/天赋加点）都推一次，客户端用它画
 * 蓝量心、ZXCV 技能栏与冷却。字段全部用简单类型 + 分隔字符串，方便扩容。</p>
 *
 * @param rank        阶级序号 0-3
 * @param mana        当前蓝量
 * @param maxMana     蓝量上限
 * @param talentPoints 可用天赋点
 * @param awakened    已觉醒系列 id，逗号分隔（顺序=Z/X/C/V）
 * @param selected    每个系列选中的技能 "元素id=技能id"，逗号分隔
 * @param cooldowns   冷却 "技能id|剩余tick"，分号分隔
 * @param talents     10 项天赋等级，逗号分隔
 * @param learned     已学习技能 id，逗号分隔（P 天赋界面 / G 技能轮盘显示用）
 */
public record MagicDataPayload(
        int rank,
        double mana,
        double maxMana,
        int talentPoints,
        String awakened,
        String selected,
        String cooldowns,
        String talents,
        String learned) implements CustomPacketPayload {

    /** 包标识：cqlove:magic_data */
    public static final Type<MagicDataPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "magic_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicDataPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MagicDataPayload::rank,
                    ByteBufCodecs.DOUBLE, MagicDataPayload::mana,
                    ByteBufCodecs.DOUBLE, MagicDataPayload::maxMana,
                    ByteBufCodecs.VAR_INT, MagicDataPayload::talentPoints,
                    ByteBufCodecs.STRING_UTF8, MagicDataPayload::awakened,
                    ByteBufCodecs.STRING_UTF8, MagicDataPayload::selected,
                    ByteBufCodecs.STRING_UTF8, MagicDataPayload::cooldowns,
                    ByteBufCodecs.STRING_UTF8, MagicDataPayload::talents,
                    ByteBufCodecs.STRING_UTF8, MagicDataPayload::learned,
                    MagicDataPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
