package com.cqgzs.cqlove.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;

import com.cqgzs.cqlove.magic.MagicData;
import com.cqgzs.cqlove.magic.MagicSync;
import com.cqgzs.cqlove.magic.PlayerMagicData;

/**
 * 蓝量瓶：喝下恢复 10 点蓝量。
 *
 * <p>26.1 起“喝东西”是数据组件驱动的：药水自身没有 {@code use()} 覆写，
 * 而是挂 {@link DataComponents#CONSUMABLE}（{@link Consumable}）组件。组件负责：</p>
 * <ul>
 *   <li>{@code use()} -> {@code consumable.startConsuming} 自动进入“使用中”状态；</li>
 *   <li>饮用动画 = {@link ItemUseAnimation#DRINK}（原版药水同款：抬手、仰头）；</li>
 *   <li>饮用过程中每 4 tick 播一次“咕咚”声 + 瓶身碎屑粒子（和药水一致）；</li>
 *   <li>时长 1.6 秒（{@code consumeSeconds}）。</li>
 * </ul>
 * 这里只保留结算逻辑（加蓝量、还玻璃瓶），不再手工 startUsingItem —— 那样拿不到饮用中的音效与粒子。
 */
public class ManaBottleItem extends Item {

    /** 每次饮用恢复的蓝量 */
    public static final double MANA_RESTORE = 10.0D;
    /** 饮用耗时（秒），与原版药水一致 */
    private static final float USE_SECONDS = 1.6F;

    public ManaBottleItem(Properties properties) {
        super(properties.component(DataComponents.CONSUMABLE, Consumable.builder()
                .consumeSeconds(USE_SECONDS)
                .animation(ItemUseAnimation.DRINK)
                .sound(SoundEvents.GENERIC_DRINK)
                .hasConsumeParticles(true)
                .build()));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        // 结算蓝量（声音/粒子已由 CONSUMABLE 组件在饮用过程中播放）
        // 需求：喝蓝量瓶不再往聊天框刷提示，只在真正回复到蓝量时冒一点蓝色光点
        if (entity instanceof ServerPlayer player) {
            PlayerMagicData data = MagicData.get(player);
            double before = data.mana();
            data.addMana(MANA_RESTORE);
            if (data.mana() > before) {
                // 立刻同步，客户端蓝心马上变化
                MagicSync.sync(player);
                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(
                            ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF4A8BFF),
                            player.getX(), player.getY() + player.getBbHeight() * 0.6D, player.getZ(),
                            10, 0.3D, 0.35D, 0.3D, 0.02D);
                }
            }
        }

        // 创造模式不消耗；生存模式消耗一瓶并返还玻璃瓶
        if (entity instanceof Player player && !player.getAbilities().instabuild) {
            stack.shrink(1);
            if (stack.isEmpty()) {
                return new ItemStack(Items.GLASS_BOTTLE);
            }
            // 有多瓶时把空瓶塞回背包
            if (!player.getInventory().add(new ItemStack(Items.GLASS_BOTTLE))) {
                player.drop(new ItemStack(Items.GLASS_BOTTLE), false);
            }
        }
        return stack;
    }
}
