package com.cqgzs.cqlove.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -&gt; 所有能看到该玩家的客户端：某玩家脚下的<b>心灵系 / 治愈系 / 毒系圆圈 / 圆环</b>状态。
 *
 * <p>和 {@link CastRingPayload}（超阶圆环）同一套机制：不生成任何实体，客户端按 UUID
 * 缓存状态并本地倒计时，画在玩家渲染层上（{@code PlayerFxLayer}），因此跟随玩家、
 * 用的是插值后的位置，跑动时完全没有拖尾。服务端只在「开启 / 结束」时发状态包，
 * 外加 2 秒一次的低频对齐，<b>不每 tick 同步实体位置</b>。</p>
 *
 * <p>同一个玩家可以同时有多个圆圈（例如安抚 + 躁动叠加），所以按 {@code variant} 分别缓存，
 * {@code variant = -1} 表示一次性清除该玩家的全部圆圈。</p>
 *
 * @param player          施法者 UUID
 * @param variant         圆圈样式（见本类常量）；{@link #CLEAR_ALL} 表示清除全部
 * @param remainingTicks  还剩多少 tick；&lt;= 0 表示清除该 variant
 * @param radius          视觉圆环半径（格），客户端按贴图填充比例换算成面片边长
 */
public record MagicCirclePayload(UUID player, int variant, int remainingTicks, float radius)
        implements CustomPacketPayload {

    /** 安抚：白色圆圈 */
    public static final int PACIFY = 0;
    /** 冲击：蓝色圆圈 */
    public static final int IMPACT = 1;
    /** 恐惧：灰色圆圈 */
    public static final int FEAR = 2;
    /** 躁动：红色圆圈 */
    public static final int AGITATION = 3;
    /** 无畏：金色圆圈 */
    public static final int VALOR = 4;
    /** 永恒幻想：白金色结界 */
    public static final int FANTASY = 5;
    /** 治愈系·宝瓶星芒座：青金星芒环 */
    public static final int HEAL_STAR = 6;
    /** 治愈系·复苏：白金薄荷绿结界环 */
    public static final int HEAL_REBIRTH = 7;
    /** 毒系·毒变：墨绿腐蚀领域环 */
    public static final int POISON_MUTATION = 8;
    /** 毒系·万噬之风：暗绿旋涡结界环 */
    public static final int POISON_GALE = 9;
    /** 祝福系·强化·祝喃：金色攻击圆圈 */
    public static final int BLESS_STRENGTH = 10;
    /** 祝福系·圣音·祝喃：金白驱散圆圈 */
    public static final int BLESS_HYMN = 11;
    /** 祝福系·灵力·祝喃：淡金魔力圆圈 */
    public static final int BLESS_SPIRIT = 12;
    /** 祝福系·神印礼赞：金色神印领域环 */
    public static final int BLESS_SEAL = 13;
    /** 祝福系·众星守护：白金星芒结界环 */
    public static final int BLESS_STARS = 14;
    /** 音系·断葬曲：琥珀音波领域环 */
    public static final int SOUND_DIRGE = 15;
    /** 音系·曲终帝陨：金橙帝陨结界环 */
    public static final int SOUND_FINALE = 16;
    /** 诅咒系·鬼刑：紫黑鬼刑领域环 */
    public static final int CURSE_GHOST = 17;
    /** 诅咒系·拘灵遣将：暗紫灵契结界环 */
    public static final int CURSE_PACT = 18;
    /** 植物系·囚牢：翠绿木牢环 */
    public static final int PLANT_PRISON = 19;
    /** 植物系·疯长：苔绿蔓生环 */
    public static final int PLANT_OVERGROWTH = 20;
    /** 植物系·鬼木手：墨绿巨木环 */
    public static final int PLANT_HAND = 21;
    /** 植物系·移花接木：嫩绿灵树结界环 */
    public static final int PLANT_GRAFT = 22;

    public static final int UNDEAD_WORLD = 23;
    /** 空间系·瞬移：青紫传送圆环（顺时针 1 秒一圈） */
    public static final int SPACE_TELEPORT = 24;

    public static final int COUNT = 25;
    public static final int CLEAR_ALL = -1;

    /** 包标识：cqlove:magic_circle */
    public static final Type<MagicCirclePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "magic_circle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicCirclePayload> CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, MagicCirclePayload::player,
                    ByteBufCodecs.VAR_INT, MagicCirclePayload::variant,
                    ByteBufCodecs.VAR_INT, MagicCirclePayload::remainingTicks,
                    ByteBufCodecs.FLOAT, MagicCirclePayload::radius,
                    MagicCirclePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
