package com.cqgzs.cqlove.item;

import com.cqgzs.cqlove.magic.NoticeApi;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.cqgzs.cqlove.config.CqloveConfig;
import com.cqgzs.cqlove.magic.MagicAttributes;
import com.cqgzs.cqlove.magic.MagicCaster;
import com.cqgzs.cqlove.magic.MagicData;
import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.magic.MagicRank;
import com.cqgzs.cqlove.magic.MagicSync;
import com.cqgzs.cqlove.magic.PlayerMagicData;
import com.cqgzs.cqlove.magic.SkillDefinition;
import com.cqgzs.cqlove.magic.SkillRegistry;

/**
 * 四本魔法书（需求文档 5.2 ~ 5.5）。用同一个物品类 + 不同 {@link Kind} 实现，避免重复代码。
 *
 * <ul>
 *   <li>觉醒书：使用后打开天赋界面技能页，双击未觉醒的系列进行觉醒（觉醒成功才消耗 1 本，
 *       觉醒后默认学会该系列第一个初阶技能）</li>
 *   <li>天赋书：学习一个当前阶级能学的技能（同一本书学全部技能），成功才消耗</li>
 *   <li>升阶书：当前阶级的技能全部学完后才能升阶，成功才消耗</li>
 *   <li>体质书：+1 天赋点（在天赋界面加点，界面上线前可先用 /magic talent 指令加）</li>
 * </ul>
 */
public class MagicBookItem extends Item {

    /** 四种书 */
    public enum Kind {
        /** 觉醒书 */
        AWAKEN,
        /** 天赋书（学技能） */
        LEARN,
        /** 升阶书 */
        RANK_UP,
        /** 体质书（天赋点） */
        BODY
    }

    private final Kind kind;

    public MagicBookItem(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // 客户端只做挥手动作，真正逻辑在服务端
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (!CqloveConfig.magicEnabled()) {
            NoticeApi.send(serverPlayer, Component.literal("魔法世界功能已在配置中关闭").withStyle(ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = player.getItemInHand(hand);
        boolean success = switch (kind) {
            case AWAKEN -> useAwaken(serverPlayer);
            case LEARN -> useLearn(serverPlayer);
            case RANK_UP -> useRankUp(serverPlayer);
            case BODY -> useBody(serverPlayer);
        };

        if (success && !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 觉醒书
    // ------------------------------------------------------------------

    private boolean useAwaken(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        if (!data.canAwaken()) {
            NoticeApi.send(player, Component.literal("当前最多只能觉醒 "
                    + Math.min(PlayerMagicData.MAX_SERIES, data.rank().maxSeries())
                    + " 个魔法系列 (阶级: " + data.rank().display() + ")").withStyle(ChatFormatting.RED));
            return false;
        }
        // 还有没觉醒的系列才打开界面
        boolean anyLeft = false;
        for (MagicElement element : MagicElement.values()) {
            if (!data.hasAwakened(element)) {
                anyLeft = true;
                break;
            }
        }
        if (!anyLeft) {
            NoticeApi.send(player, Component.literal("已经没有可觉醒的魔法系列了").withStyle(ChatFormatting.RED));
            return false;
        }

        // 需求（2026-11-13）：觉醒书不再随机觉醒 —— 打开天赋界面技能页，
        // 由玩家双击想觉醒的系列；觉醒成功才扣 1 本书（见 MagicNetworkHandler.handleAwaken）。
        // 这里只发“打开界面”的包，返回 false = 使用时不消耗书。
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.cqgzs.cqlove.network.OpenTalentScreenPayload());
        return false;
    }

    // ------------------------------------------------------------------
    // 天赋书（学技能）
    // ------------------------------------------------------------------

    private boolean useLearn(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        if (data.awakened().isEmpty()) {
            NoticeApi.send(player, Component.literal("请先使用觉醒书觉醒一个魔法系列").withStyle(ChatFormatting.RED));
            return false;
        }

        // 按觉醒顺序、技能树顺序找第一个“能学但还没学”的技能
        for (MagicElement element : data.awakened()) {
            for (SkillDefinition skill : SkillRegistry.of(element)) {
                if (data.isLearned(skill.id())) {
                    continue;
                }
                if (skill.tier().ordinal() > data.rank().maxLearnableTier()) {
                    break; // 该系列后面的技能更高阶，肯定也不能学
                }
                String error = MagicCaster.learn(player, skill);
                if (error == null) {
                    feedback(player, element.color(), SoundEvents.AMETHYST_BLOCK_CHIME, 1.3F);
                    return true;
                }
                NoticeApi.send(player, Component.literal(error).withStyle(ChatFormatting.RED));
                return false;
            }
        }
        NoticeApi.send(player, Component.literal("当前阶级能学的技能都已经学会了, 先升阶吧")
                .withStyle(ChatFormatting.YELLOW));
        return false;
    }

    // ------------------------------------------------------------------
    // 升阶书
    // ------------------------------------------------------------------

    private boolean useRankUp(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        if (!data.rank().canRankUp()) {
            NoticeApi.send(player, Component.literal("已经是最高阶级: 超阶").withStyle(ChatFormatting.RED));
            return false;
        }
        if (data.awakened().isEmpty()) {
            NoticeApi.send(player, Component.literal("请先觉醒至少一个魔法系列").withStyle(ChatFormatting.RED));
            return false;
        }

        // 需求 5.4：当前阶级的技能必须全部学完才能升阶
        for (MagicElement element : data.awakened()) {
            for (SkillDefinition skill : SkillRegistry.of(element, com.cqgzs.cqlove.magic.SkillTier.byOrdinal(data.rank().ordinal()))) {
                if (!data.isLearned(skill.id())) {
                    NoticeApi.send(player, Component.literal("还没学完当前阶级的技能: 缺少【"
                            + element.display() + "·" + skill.name() + "】").withStyle(ChatFormatting.RED));
                    return false;
                }
            }
        }

        MagicRank next = data.rank().next();
        data.setRank(next);
        MagicAttributes.apply(player);
        MagicSync.sync(player);
        feedback(player, 0xFFFFD24A, SoundEvents.PLAYER_LEVELUP, 1.0F);
        return true;
    }

    // ------------------------------------------------------------------
    // 体质书
    // ------------------------------------------------------------------

    private boolean useBody(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        data.addTalentPoints(1);
        MagicSync.sync(player);
        feedback(player, 0xFF6CFFB0, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F);
        return true;
    }

    /**
     * 成功反馈：只放音效 + 粒子，<b>不发聊天消息</b>。
     *
     * <p>需求：使用各种书不再往聊天框刷提示，成功与否由音效/粒子区分
     * （失败仍有红字说明原因，否则玩家不知道为什么没生效）。</p>
     */
    public static void feedback(ServerPlayer player, int color, net.minecraft.sounds.SoundEvent sound, float pitch) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), sound,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, pitch);
        level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(
                        net.minecraft.core.particles.ParticleTypes.ENTITY_EFFECT, color),
                player.getX(), player.getY() + player.getBbHeight() * 0.6D, player.getZ(),
                26, 0.4D, 0.6D, 0.4D, 0.05D);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                player.getX(), player.getY() + 1.3D, player.getZ(),
                18, 0.5D, 0.6D, 0.5D, 0.7D);
    }
}
