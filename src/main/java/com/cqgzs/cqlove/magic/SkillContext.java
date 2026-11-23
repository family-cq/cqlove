package com.cqgzs.cqlove.magic;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 一次技能释放的上下文。
 *
 * <p>技能实现只需要用它拿“施法原点 / 鼠标指向点 / 指向的生物”，
 * 以及 {@link #damage(LivingEntity, double)} 结算伤害（内部已带 PVP 校验与魔暴击）。</p>
 *
 * <p>指向点规则（需求文档：超过施法范围则按施法范围计算）：</p>
 * <ol>
 *   <li>从玩家眼睛沿视线取 {@code 技能范围} 长度；</li>
 *   <li>中间被方块挡住就停在方块处；</li>
 *   <li>中间有生物则优先取最近的那只生物。</li>
 * </ol>
 *
 * <p><b>范围唯一数据源</b>：{@link MagicRank#castRange()}（初阶 10 / 中阶 15 / 高阶 20 /
 * 超阶 30，对该玩家可用的所有技能生效，含低阶技能）。技能实现必须通过
 * {@link #targetPos()}、{@link #range()}、{@link #travelTicks(double)} 或
 * {@link #clampDistance(double)} 取范围，不要再写死「16 格 / 20 格 / 24 tick」之类的上限，
 * 否则升阶后技能范围不会跟着变大。</p>
 */
public final class SkillContext {

    private final ServerPlayer player;
    private final SkillDefinition skill;
    private final ServerLevel level;
    private final double range;
    /** 释放时是否按住 Ctrl（石盾等技能用它区分“对自己 / 对目标”） */
    private final boolean ctrlDown;

    private @Nullable Vec3 targetPos;
    private @Nullable LivingEntity targetEntity;
    private boolean resolved;

    public SkillContext(ServerPlayer player, SkillDefinition skill) {
        this(player, skill, false);
    }

    public SkillContext(ServerPlayer player, SkillDefinition skill, boolean ctrlDown) {
        this.player = player;
        this.skill = skill;
        this.level = (ServerLevel) player.level();
        // 施法范围由玩家阶级决定（初阶10/中阶15/高阶20/超阶30），对低阶技能同样生效
        this.range = skill.range(MagicData.get(player).rank());
        this.ctrlDown = ctrlDown;
    }

    public ServerPlayer player() {
        return player;
    }

    public SkillDefinition skill() {
        return skill;
    }

    public ServerLevel level() {
        return level;
    }

    /** 施法范围（格）：初阶 10 / 中阶 15 / 高阶 20 / 超阶 30，含低阶技能 */
    public double range() {
        return range;
    }

    /**
     * 按施法范围换算「投射物最多可飞行的 tick 数」——飞满范围即消失，
     * 不允许技能自己写死飞行时长，否则升阶后范围不会变大。
     */
    public int travelTicks(double blocksPerTick) {
        return Math.max(1, (int) Math.ceil(range / Math.max(0.01D, blocksPerTick)));
    }

    /** 把期望的水平距离钳制到 [0, 施法范围] */
    public double clampDistance(double desired) {
        return Math.max(0.0D, Math.min(range, desired));
    }

    /** 释放时玩家是否按住 Ctrl（石盾：Ctrl=对自己释放，否则对视线上的生物） */
    public boolean ctrlDown() {
        return ctrlDown;
    }

    /** 施法起点：玩家眼睛位置（技能都“从手上释放”，这里取眼睛高度略下方更自然） */
    public Vec3 origin() {
        Vec3 eye = player.getEyePosition();
        return eye.add(player.getLookAngle().scale(0.4D)).add(0.0D, -0.15D, 0.0D);
    }

    /** 鼠标指向的位置（已按施法范围截断） */
    public Vec3 targetPos() {
        resolve();
        return targetPos == null ? origin() : targetPos;
    }

    /** 视线上的目标生物，没有则为 null */
    public @Nullable LivingEntity targetEntity() {
        resolve();
        return targetEntity;
    }

    /** 结算魔法伤害（含 PVP 判定、魔力加成、魔暴击），伤害数字用本技能系列的代表色 */
    public boolean damage(LivingEntity target, double skillDamage) {
        return MagicDamage.magicDamage(player, target, skillDamage, skill.element());
    }

    /** 沿视线找目标：先看方块，再看生物，生物优先且取最近的 */
    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));

        HitResult blockHit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 stop = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

        LivingEntity entity = findEntityAlong(eye, stop);
        if (entity != null) {
            targetEntity = entity;
            // 命中点取生物碰撞箱上的点，保证特效落在生物身上
            stop = entity.getBoundingBox().inflate(0.3D).clip(eye, stop).orElse(entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D));
        }
        targetPos = stop;
    }

    /** 在眼睛到 stop 的线段附近找最近的生物 */
    private @Nullable LivingEntity findEntityAlong(Vec3 eye, Vec3 stop) {
        AABB area = new AABB(eye, stop).inflate(1.0D);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (candidate == player || !candidate.isAlive() || candidate.isSpectator()) {
                continue;
            }
            var hit = candidate.getBoundingBox().inflate(0.35D).clip(eye, stop);
            if (hit.isEmpty()) {
                continue;
            }
            double dist = hit.get().distanceToSqr(eye);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }
}
