package com.cqgzs.cqlove.magic;

import java.util.Map;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 特定生物的攻击改为对应系列的<b>魔法伤害</b>（需求 2026-11-13）。
 *
 * <p>映射表：</p>
 * <ul>
 *   <li>潜影贝 -&gt; 空间系</li>
 *   <li>岩浆怪 / 烈焰人 -&gt; 火系</li>
 *   <li>坚守者（监守者） -&gt; 暗影系 + 诅咒系（每次随机其一）</li>
 *   <li>史莱姆 -&gt; 植物系</li>
 *   <li>幻翼 / 旋风人 -&gt; 风系</li>
 *   <li>守卫者 -&gt; 水系</li>
 *   <li>远古守卫者 -&gt; 水系 + 诅咒系（每次随机其一）</li>
 *   <li>苦力怕 -&gt; 火系 + 土系（每次随机其一）</li>
 * </ul>
 *
 * <p>实现方式：在 {@code LivingIncomingDamageEvent} 里拦截这些生物（直接攻击者或弹射物主人）
 * 对任意生物的伤害，先让水盾/水花天幕尝试吸收（魔法伤害可挡），然后把原伤害取消、
 * 按原伤害数值改走该生物对应的<b>元素魔法伤害类型</b>（见 {@link MagicDamageTypes}）重新结算——
 * 这样魔抗（风之翼减半）、系列色伤害数字、元素粒子、元素死亡提示都会自然生效。转换后的魔法伤害
 * 再次进入事件时由 {@link #converting} 标记挡住，不会递归。其余生物维持原版伤害，后续再补充。</p>
 */
public final class MobMagic {

    /** 生物类型 -> 攻击携带的元素（两种 = 每次攻击随机取其一） */
    private static final Map<EntityType<?>, MagicElement[]> MOB_ELEMENTS = Map.ofEntries(
            Map.entry(EntityType.SHULKER, new MagicElement[]{MagicElement.SPACE}),
            Map.entry(EntityType.MAGMA_CUBE, new MagicElement[]{MagicElement.FIRE}),
            Map.entry(EntityType.BLAZE, new MagicElement[]{MagicElement.FIRE}),
            Map.entry(EntityType.WARDEN, new MagicElement[]{MagicElement.SHADOW, MagicElement.CURSE}),
            Map.entry(EntityType.SLIME, new MagicElement[]{MagicElement.PLANT}),
            Map.entry(EntityType.PHANTOM, new MagicElement[]{MagicElement.WIND}),
            Map.entry(EntityType.GUARDIAN, new MagicElement[]{MagicElement.WATER}),
            Map.entry(EntityType.ELDER_GUARDIAN, new MagicElement[]{MagicElement.WATER, MagicElement.CURSE}),
            Map.entry(EntityType.CREEPER, new MagicElement[]{MagicElement.FIRE, MagicElement.EARTH}),
            Map.entry(EntityType.BREEZE, new MagicElement[]{MagicElement.WIND}));

    /** 重入保护：转换后的魔法伤害再次进入事件时不再转换（防递归） */
    private static boolean converting = false;

    private MobMagic() {
    }

    /**
     * 尝试把一次伤害转换成生物的元素魔法伤害。
     *
     * @return true = 已处理（原事件已取消 / 已改为魔法伤害），调用方直接 return
     */
    public static boolean tryConvert(LivingIncomingDamageEvent event) {
        if (converting || event.isCanceled()) {
            return false;
        }
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) {
            return false;
        }
        // 找攻击来源：先看直接攻击者（近战），再看弹射物主人（潜影贝导弹/烈焰人火球/守卫者光束）
        MagicElement[] elements = null;
        LivingEntity attacker = null;
        if (event.getSource().getDirectEntity() instanceof LivingEntity direct) {
            elements = MOB_ELEMENTS.get(direct.getType());
            attacker = direct;
        }
        if (elements == null && event.getSource().getEntity() instanceof LivingEntity causing) {
            elements = MOB_ELEMENTS.get(causing.getType());
            attacker = causing;
        }
        if (elements == null || attacker == null) {
            return false;
        }
        float amount = event.getAmount();
        if (amount <= 0.0F || !attacker.isAlive()) {
            return false;
        }

        // 该生物这次攻击携带的元素（双元素生物随机取一种）
        MagicElement element = elements.length == 1
                ? elements[0]
                : elements[level.getRandom().nextInt(elements.length)];
        // 元素专属的魔法伤害类型（不再是通用的 indirectMagic）
        DamageSource magic = MagicDamageTypes.source(level, element, attacker);

        // 水盾 / 水花天幕优先吸收这次魔法伤害（魔法可挡，物理不可挡 -> 现在是魔法了）
        if (WaterMagic.tryBlockMagic(victim)) {
            event.setCanceled(true);
            return true;
        }

        // 取消原伤害，按原数值改为魔法伤害重新结算
        event.setCanceled(true);
        converting = true;
        boolean hurt;
        try {
            hurt = victim.hurtServer(level, magic, amount);
        } finally {
            converting = false;
        }
        if (hurt) {
            // 系列色伤害数字 + 元素色粒子，让“这是 XX 系魔法伤害”看得见
            Vec3 pos = victim.position().add(0.0D, victim.getBbHeight() + 0.25D, 0.0D);
            DamageNumbers.spawn(level, pos, amount, element.color());
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, element.color()),
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.6D, victim.getZ(),
                    10, 0.3D, 0.4D, 0.3D, 0.02D);
        }
        return true;
    }
}
