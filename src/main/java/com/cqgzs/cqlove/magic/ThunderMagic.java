package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.Identifier;

/**
 * 雷系中阶 ~ 超阶技能实现（雷系初阶 3 个在 {@link SkillRegistry} 里，沿用同一套特效）。
 *
 * <p>对照需求文档：</p>
 * <ul>
 *   <li>霹雳·轰顶：5 条天雷，每条 9 点；5 只以内生物分摊，没有生物就砸方块 5 次</li>
 *   <li>霹雳·夜叉：落点 + 前后左右共 5 格同时天雷，每条 60 点</li>
 *   <li>霹雳·狂舞：同上 5 格，分 3 波（间隔 1 秒），共 15 条，每条 50 点</li>
 *   <li>寂雷死光：3×3 共 9 格天雷，300 点</li>
 *   <li>雷神降临：60 秒雷神 buff（属性加成 + 四免疫 + 攻击附雷），buff 结束再进冷却</li>
 * </ul>
 */
public final class ThunderMagic {

    /** 雷神 buff 的攻击加成属性修饰器 id */
    private static final Identifier THUNDER_GOD_ATTACK =
            Identifier.fromNamespaceAndPath(com.cqgzs.cqlove.PlatformMods.MODID, "thunder_god_attack");


    private ThunderMagic() {
    }

    // ------------------------------------------------------------------
    // 中阶·霹雳
    // ------------------------------------------------------------------

    /** 蓄力前摇时长（tick）：按下技能键即触发，无需长按 */
    private static final int WINDUP_TICKS = 15;

    /**
     * 轰顶：5 条天雷（每 3 tick 一条落向同一目标点）。
     * 落点附近 5 格内最多 5 只生物时分别命中，没有生物就砸同一方块位置 5 次。
     * 先经过一段"蓄力-炸开"前摇。
     */
    public static void skyStrike(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        windupThen(ctx, () -> {
            // 前摇结束才取目标，保证劈的是蓄力结束时真正在那里的生物
            List<LivingEntity> targets = nearbyTargets(ctx, center, 5.0D, 5);
            MagicScheduler.every(level, 3, 5 * 3, () -> {
                // 每轮挑一个还没被打过的目标，目标用完就砸方块位置
                LivingEntity target = nextTarget(targets);
                Vec3 to = target != null
                        ? target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D)
                        : center;
                MagicDamage.skyBolt(level, to);
                if (target != null) {
                    ctx.damage(target, 9.0D);
                }
            }, null);
        });
    }

    /** 夜叉：落点 + 前后左右 5 格落雷（中心先落，四方随后，雷声滚成一片），每条 60 点，先蓄力 */
    public static void yaksha(SkillContext ctx) {
        windupThen(ctx, () -> fivePointBolts(ctx, 60.0D));
    }

    /** 狂舞：同上 5 格落雷，分 3 波（间隔 1 秒），共 15 条，每条 50 点，先蓄力 */
    public static void madDance(SkillContext ctx) {
        ServerLevel level = ctx.level();
        windupThen(ctx, () -> MagicScheduler.every(level, 20, 3 * 20, () -> fivePointBolts(ctx, 50.0D), null));
    }

    // ------------------------------------------------------------------
    // 高阶·寂雷死光
    // ------------------------------------------------------------------

    /**
     * 寂雷死光：以落点为中心的 3×3 共 9 格全部落雷，300 点。
     * 视觉：中心雷先落，外圈 8 条以螺旋顺序每 tick 一条接连砸下，最后中央爆一团巨型白闪收尾。
     */
    public static void deathRay(SkillContext ctx) {
        windupThen(ctx, () -> deathRayBurst(ctx));
    }

    /** 寂雷死光的爆发段：蓄力结束后九雷螺旋砸下 + 巨型白闪收尾 + 结算 300 点 */
    private static void deathRayBurst(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        BlockPos base = BlockPos.containing(center);
        // 螺旋顺序：中心 → 上 → 右上 → 右 → 右下 → 下 → 左下 → 左 → 左上
        int[][] spiral = {
                {0, 0}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
        };
        for (int i = 0; i < spiral.length; i++) {
            int[] offset = spiral[i];
            Vec3 pos = new Vec3(base.getX() + offset[0] + 0.5D, center.y, base.getZ() + offset[1] + 0.5D);
            MagicScheduler.later(level, i, () -> MagicDamage.skyBolt(level, pos));
        }
        // 全部落完后在中央补一团巨型白闪 + 大范围电弧，收尾的“死光”感
        MagicScheduler.later(level, spiral.length + 1, () -> {
            level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(
                    ParticleTypes.FLASH, 0xFFFFFFFF), center.x, center.y + 1.0D, center.z,
                    6, 1.2D, 0.8D, 1.2D, 0.0D);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y + 0.5D, center.z,
                    80, 2.0D, 1.0D, 2.0D, 0.5D);
        });
        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class, new AABB(center, center).inflate(2.2D, 3.0D, 2.2D))) {
            if (entity != ctx.player() && entity.isAlive()) {
                ctx.damage(entity, 300.0D);
            }
        }
    }

    // ------------------------------------------------------------------
    // 超阶·雷神降临
    // ------------------------------------------------------------------

    /**
     * 雷神降临：60 秒 buff。
     * 属性 +100（魔力/蓝量在 {@link PlayerMagicData}，魔暴伤在 {@link MagicDamage}，攻击在这里挂修饰器）；
     * 四免疫与攻击附雷在 {@link MagicEvents} 判定；冷却在 buff 结束时才开始算。
     */
    public static void thunderGod(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        PlayerMagicData data = MagicData.get(player);
        data.startThunderGod();
        var attack = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.addOrUpdateTransientModifier(
                    new AttributeModifier(THUNDER_GOD_ATTACK, 100.0D, AttributeModifier.Operation.ADD_VALUE));
        }
        data.addMana(100.0D);
        MagicSync.sync(player);

        ServerLevel level = ctx.level();
        // 从天上向玩家 Y 轴落一条雷（文档：从天上向玩家 Y 轴坐标落下一条雷电）
        MagicDamage.skyBolt(level, player.position());
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 1.0D, player.getZ(),
                30, 0.6D, 1.0D, 0.6D, 0.1D);
        MagicDamage.playSound(level, player.position(), SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0F, 1.0F);
        // 脚下法阵是客户端渲染层（PlayerFxLayer），由 MagicFxSync 广播开关位，这里不直接碰
    }

    /** 每 tick 驱动雷神 buff：周围环绕雷电粒子；到期卸加成、技能进 60 秒冷却 */
    public static void tickBuff(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        if (!data.thunderGodActive()) {
            return;
        }
        // 每 5 tick 补一次环绕雷电，避免每 tick 发包
        if (data.thunderGodTicks() % 5 == 0 && player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    player.getX(), player.getY() + 1.0D, player.getZ(), 4, 0.7D, 0.9D, 0.7D, 0.02D);
        }
        if (data.tickThunderGod()) {
            var attack = player.getAttribute(Attributes.ATTACK_DAMAGE);
            if (attack != null) {
                attack.removeModifier(THUNDER_GOD_ATTACK);
            }
            // buff 结束才开始 60 秒冷却（文档：buff 完 + 冷却完才能再放）
            data.endSuperRing("thunder_3_1");
            data.setCooldown("thunder_3_1", player.level().getGameTime() + 60L * 20L);
            MagicSync.sync(player);
        }
    }

    /** 雷神 buff 期间：玩家攻击命中时的附加雷电特效（MagicEvents 调用，纯表现，不额外结算伤害） */
    public static void attackSpark(ServerPlayer player, LivingEntity target) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        MagicDamage.line(level, hit.add(0.0D, 3.0D, 0.0D), hit, ParticleTypes.ELECTRIC_SPARK, 0.4D);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y, hit.z, 6, 0.3D, 0.3D, 0.3D, 0.05D);
    }

    /**
     * "蓄力-炸开"前摇：按下技能键即触发（无需长按）。
     * 目标点先出现一圈向内收缩、边收边旋转的电弧（0.75 秒），中心电弧向上升腾且越来越密；
     * 收拢瞬间炸开一团白闪 + 电流炸响，随后执行真正的落雷效果。
     */
    private static void windupThen(SkillContext ctx, Runnable effect) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        // 起手：一声蓄能鸣响（高音调，提示开始聚力）
        MagicDamage.playSound(level, center, SoundEvents.BEACON_ACTIVATE, 0.7F, 1.8F);
        int[] elapsed = {0};
        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int windup = IceMagic.castTicks(ctx.player(), WINDUP_TICKS);
        MagicScheduler.every(level, 1, windup, () -> {
            elapsed[0]++;
            double progress = (double) elapsed[0] / windup;
            // 1. 收缩中的旋转电弧环：半径从 2.5 格收拢到 0
            double radius = 2.5D * (1.0D - progress);
            int points = 10;
            for (int i = 0; i < points; i++) {
                double angle = i * Math.PI * 2.0D / points + progress * 3.0D;
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        center.x + Math.cos(angle) * radius, center.y + 0.15D, center.z + Math.sin(angle) * radius,
                        1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
            // 2. 中心电弧升腾，越接近爆发越密
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    center.x, center.y + 0.3D, center.z,
                    2 + (int) (progress * 8.0D), 0.15D, 0.5D, 0.15D, 0.03D);
            // 3. 收拢瞬间：小炸闪 + 一记电流炸响，紧接着天雷落下
            if (elapsed[0] == windup) {
                level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(
                        ParticleTypes.FLASH, 0xFFEAF6FF), center.x, center.y + 0.6D, center.z,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
                MagicDamage.playSound(level, center, SoundEvents.CONDUIT_ATTACK_TARGET, 1.0F, 1.3F);
            }
        }, effect);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 落点 + 前后左右（±1 格）共 5 个位置落雷。
     * 视觉：中心雷先落，四方雷每隔 2 tick 错开落下，雷声滚成一片、满屏闪电接连炸开。
     * 每条雷命中该格附近的生物（同一只生物一次技能只被打一次）。
     */
    private static void fivePointBolts(SkillContext ctx, double damage) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        BlockPos base = BlockPos.containing(center);
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<LivingEntity> hit = new ArrayList<>();
        for (int i = 0; i < offsets.length; i++) {
            int[] offset = offsets[i];
            Vec3 pos = new Vec3(base.getX() + offset[0] + 0.5D, center.y, base.getZ() + offset[1] + 0.5D);
            MagicScheduler.later(level, i * 2, () -> {
                MagicDamage.skyBolt(level, pos);
                for (LivingEntity entity : level.getEntitiesOfClass(
                        LivingEntity.class, new AABB(pos, pos).inflate(0.9D, 2.0D, 0.9D))) {
                    if (entity == ctx.player() || !entity.isAlive() || hit.contains(entity)) {
                        continue;
                    }
                    hit.add(entity);
                    ctx.damage(entity, damage);
                }
            });
        }
    }

    /** 取落点附近最近的 n 只生物（不含施法者） */
    private static List<LivingEntity> nearbyTargets(SkillContext ctx, Vec3 center, double radius, int limit) {
        List<LivingEntity> targets = new ArrayList<>(ctx.level().getEntitiesOfClass(
                LivingEntity.class, new AABB(center, center).inflate(radius)));
        targets.removeIf(entity -> entity == ctx.player() || !entity.isAlive());
        targets.sort((a, b) -> Double.compare(a.distanceToSqr(center), b.distanceToSqr(center)));
        while (targets.size() > limit) {
            targets.remove(targets.size() - 1);
        }
        return targets;
    }

    /** 依次取出还没被本次技能打过的目标（列表里的元素用完即从列表移除） */
    private static LivingEntity nextTarget(List<LivingEntity> targets) {
        if (targets.isEmpty()) {
            return null;
        }
        LivingEntity picked = targets.get(0);
        targets.remove(0);
        return picked.isAlive() ? picked : nextTarget(targets);
    }
}
