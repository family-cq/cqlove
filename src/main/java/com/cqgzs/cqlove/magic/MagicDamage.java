package com.cqgzs.cqlove.magic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 魔法伤害结算（需求文档 5.9，简化实现）。
 *
 * <p>公式：</p>
 * <pre>
 * 技能基础魔法伤害 = 技能伤害值 + 玩家魔力值（后续再加武器/套装魔力）
 * 元素精通缩放 = 基础 × 元素精通 / 100（初始精通 100 = 不增不减）
 * 魔暴击判定：Random(0,1) &lt; 魔暴击率（初始 5% + 天赋）
 * 最终伤害 = 魔暴击 ? 缩放后 × (1 + 魔暴击伤害%)（初始 100% = 翻倍） : 缩放后
 * </pre>
 *
 * <p>说明：文档里的“基础伤害 × 基础伤害”平方公式在 60 点技能上会直接放大到 3600，
 * 明显超出设计意图（文档也注明“模糊，可按自己理解计算”），这里改用线性暴击倍率，
 * 系数集中在 {@link MagicTalent} 与本法，后续要调只改一处。</p>
 */
public final class MagicDamage {

    private MagicDamage() {
    }

    /**
     * 对目标造成一次<b>本系列</b>的魔法伤害，并在目标头顶弹出系列色的伤害数字（0.5 秒）。
     *
     * <p>伤害类型按系列区分（{@link MagicDamageTypes}），所以火系技能就是火系魔法伤害、
     * 水系技能就是水系魔法伤害，死亡提示、其他模组的元素判定都能对应上。</p>
     *
     * @param caster      施法者
     * @param target      受击目标
     * @param skillDamage 技能面板伤害（需求文档里写的“技能伤害为 N”）
     * @param element     技能所属系列（同时决定伤害类型与伤害数字颜色）
     * @return 是否真正造成了伤害
     */
    public static boolean magicDamage(ServerPlayer caster, LivingEntity target, double skillDamage, MagicElement element) {
        if (target == caster || !target.isAlive()) {
            return false;
        }
        // 水系：目标身上的水盾 / 水花天幕先把这次魔法伤害吃掉（物理伤害不走这里，挡不住）
        if (WaterMagic.tryBlockMagic(target)) {
            return false;
        }
        // 元素反应晶核盾：抵挡一次魔法攻击（消耗一层）
        if (ElementShield.tryBlock(target, (ServerLevel) caster.level())) {
            return false;
        }
        // 需求文档 5.1.1：所有技能都要走 PVP API
        if (target instanceof Player victim && !com.cqgzs.cqlove.lan.PvpApi.canHurt(caster, victim)) {
            return false;
        }

        PlayerMagicData data = MagicData.get(caster);
        double base = skillDamage + data.magicPower();

        // 元素精通：初始 100，最终魔法伤害按 精通/100 缩放（100 = 100%，不增不减）
        base *= data.elementMastery() / 100.0D;

        // 土系·角斗场：场主魔力 +20%，被锁定的生物魔力 -50%
        base *= EarthMagic.casterMagicMultiplier(caster);

        // 魔暴击：初始 5% 概率 / 100% 暴伤（即暴击翻倍），天赋再加成
        double critChance = data.magicCritChance() / 100.0D;
        double critDamage = data.magicCritDamage() / 100.0D;
        if (data.fireGodActive() || data.thunderGodActive()) {
            // 火神/雷神 buff：魔暴击伤害 +100（+100%）
            critDamage += 1.0D;
        }
        boolean crit = caster.getRandom().nextDouble() < critChance;
        double finalDamage = crit ? base * (1.0D + critDamage) : base;

        ServerLevel level = (ServerLevel) caster.level();
        boolean hurt = target.hurtServer(level, MagicDamageTypes.source(level, element, caster), (float) finalDamage);
        if (hurt) {
            // 元素附着与反应统一在 MagicEvents.onLivingDamage 的全局兜底里处理，
            // 这样召唤物/怪物等非玩家施法者的元素魔法伤害也会被覆盖。

            // 头顶弹伤害数字（魔法用系列色），0.5 秒后自动消失
            DamageNumbers.spawn(level, target.position().add(0.0D, target.getBbHeight() + 0.25D, 0.0D),
                    finalDamage, element.color());
            // 命中处的系列色光点，让“这一下是什么系”更直观
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, element.color()),
                    target.getX(), target.getY() + target.getBbHeight() * 0.6D, target.getZ(),
                    10, 0.3D, 0.35D, 0.3D, 0.02D);
            if (crit) {
                // 暴击时补一圈暴击粒子，给玩家反馈
                level.sendParticles(ParticleTypes.CRIT,
                        target.getX(), target.getY() + target.getBbHeight() * 0.6D, target.getZ(),
                        8, 0.3D, 0.3D, 0.3D, 0.05D);
            }
        }
        return hurt;
    }

    /** 特效：两点之间画一条粒子线（用于雷电/光晕拖尾，按步长控制数量避免卡顿） */
    public static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, double step) {
        double distance = from.distanceTo(to);
        if (distance <= 0.01D) {
            return;
        }
        int points = (int) Math.min(80, Math.max(2, distance / Math.max(0.2D, step)));
        Vec3 delta = to.subtract(from).scale(1.0D / points);
        for (int i = 0; i <= points; i++) {
            Vec3 pos = from.add(delta.scale(i));
            level.sendParticles(particle, pos.x, pos.y, pos.z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
    }

    /**
     * 特效：从天空落下的雷电（用于“怒击/轰顶/夜叉/狂舞/寂雷死光”这类技能）。
     *
     * <p>视觉组合：原版闪电实体（纯视觉：真实闪电渲染 + 全屏闪光 + 自带雷声，不点方块不伤生物）
     * + 命中点电弧爆散 + 白色闪光 + 沿地面扩散的电弧冲击环。</p>
     */
    public static void skyBolt(ServerLevel level, Vec3 target) {
        // 1. 真正的闪电实体：visualOnly = 只渲染闪电和闪光，不造成伤害/不点着方块
        net.minecraft.world.entity.LightningBolt bolt =
                new net.minecraft.world.entity.LightningBolt(net.minecraft.world.entity.EntityType.LIGHTNING_BOLT, level);
        bolt.setVisualOnly(true);
        bolt.setPos(target.x, target.y, target.z);
        level.addFreshEntity(bolt);

        // 2. 命中点：电弧向四周爆散 + 一团白色闪光
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                target.x, target.y + 0.3D, target.z, 40, 0.6D, 0.5D, 0.6D, 0.4D);
        level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFEAF6FF),
                target.x, target.y + 0.5D, target.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);

        // 3. 地面冲击环：12 条短电弧沿地面向外扩散
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2.0D / 12.0D;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            line(level, target.add(dx * 0.3D, 0.15D, dz * 0.3D),
                    target.add(dx * 2.2D, 0.2D, dz * 2.2D), ParticleTypes.ELECTRIC_SPARK, 0.45D);
        }

        // 音效由闪电实体自带（客户端生成时播放雷声 + 落雷炸响），不再重复播放
    }

    /** 播放一个音效（位置音源，附近玩家都能听到） */
    public static void playSound(ServerLevel level, Vec3 pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
    }
}
