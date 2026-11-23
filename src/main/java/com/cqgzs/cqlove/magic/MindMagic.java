package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.network.MagicCirclePayload;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 心灵系 8 个技能（需求 2026-11-14）。
 *
 * <p>初阶「心灵」：</p>
 * <ul>
 *   <li>安抚：脚下白色圆圈（直径 = 施法范围），圈内生物（含敌对生物）全部变成友好生物 30 秒；</li>
 *   <li>冲击：蓝色圆圈，圈内敌对生物物理/魔法防御各 -10%，30 秒；</li>
 *   <li>心灵之墙：粒子墙把准星生物围住，禁魔 + 魔法防御 -20%，30 秒。</li>
 * </ul>
 * <p>中阶「心灵之涟」：</p>
 * <ul>
 *   <li>恐惧：灰色圆圈，圈内敌对生物缓慢 + 失明，每秒 8 点心灵系伤害，30 秒；</li>
 *   <li>躁动：红色圆圈，圈内敌对生物每秒 10 点伤害、物理防御 -30% / 魔法防御 -50%，30 秒；</li>
 *   <li>无畏：金色圆圈，圈内友好生物与玩家加速 + 幸运、攻击 +12、魔力值 +12、
 *       物理防御 +10% / 魔法防御 +20%，30 秒。</li>
 * </ul>
 * <p>高阶「摄魄控心」：红色符文连成一条线锁住敌对生物 40 秒；玩家按住 Alt 时
 * 右键让被控生物朝准星位置移动、左键让它攻击准星生物。生物死亡立即进冷却；
 * 40 秒到还活着则原地爆裂造成 300 点爆炸伤害（不破坏地形）。</p>
 * <p>超阶「永恒幻想」：玩家专属白金色结界 60 秒，物理防御 +50%、魔法防御 +100%，
 * 免疫火焰/爆炸/中毒/失明/岩浆/雷电，魔力值 +100、魔暴击 +80、魔暴击伤害 +200。</p>
 *
 * <p>8 个技能全部「效果结束才进入冷却」：施法瞬间清掉预写冷却并锁住该技能，
 * 效果结束（或目标提前死亡）时由 {@link #finishTimed} 写入完整冷却，
 * 超阶圆环也会在同一刻收掉。</p>
 *
 * <p>防御增减一律在 {@link MagicEvents} 的伤害事件里以倍率结算：
 * 「防御 -N%」= 受伤 ×(1+N%)，「防御 +N%」= 受伤 ×1/(1+N%)，
 * 与风之翼「魔抗 +50 → 魔法伤害减半」保持同一口径，多个效果乘算叠加。</p>
 */
public final class MindMagic {

    private MindMagic() {
    }

    // ---------------- 时长 / 数值 ----------------
    private static final int CIRCLE_TICKS = 30 * 20;
    private static final int WALL_TICKS = 30 * 20;
    private static final int CONTROL_TICKS = 40 * 20;
    private static final int FANTASY_TICKS = 60 * 20;

    private static final double FEAR_DPS = 8.0D;
    private static final double AGITATION_DPS = 10.0D;
    private static final double CONTROL_BURST = 300.0D;
    private static final double CONTROL_SPLASH = 150.0D;
    private static final double WALL_RADIUS = 2.0D;
    private static final int SIGIL_COUNT = 5;
    private static final int ATTACK_COOLDOWN = 10;
    /** 被控生物进入这个距离就挥击（原 3.2 太苛刻，配合 AI 抢路时几乎打不到） */
    private static final double ATTACK_REACH = 4.0D;
    /** 永恒幻想白金结界的视觉半径（格），与旧版 5 格边长的贴地面片保持一致 */
    private static final float FANTASY_RADIUS = 2.25F;
    /** 防御 -10% = 受伤 ×1.10（冲击） */
    private static final double IMPACT_MUL = 1.10D;
    /** 躁动：物理 -30% / 魔法 -50% */
    private static final double AGITATION_PHYS = 1.30D;
    private static final double AGITATION_MAGIC = 1.50D;
    /** 心灵之墙：魔法防御 -20% */
    private static final double WALL_MAGIC = 1.20D;
    /** 无畏：物理 +10% / 魔法 +20% */
    private static final double VALOR_PHYS = 1.0D / 1.10D;
    private static final double VALOR_MAGIC = 1.0D / 1.20D;
    /** 永恒幻想：物理 +50% / 魔法 +100% */
    private static final double FANTASY_PHYS = 1.0D / 1.50D;
    private static final double FANTASY_MAGIC = 1.0D / 2.00D;

    private static final Identifier VALOR_ATTACK_ID = id("mind_valor_attack");

    /** 客户端 -> 服务端控制指令（见 {@code MindControlPayload}） */
    public static final int CONTROL_MOVE = 1;
    public static final int CONTROL_ATTACK = 2;

    // ---------------- 运行时状态（不存档） ----------------
    private enum Kind { PACIFY, IMPACT, FEAR, AGITATION, VALOR }

    private static final List<Circle> CIRCLES = new ArrayList<>();
    /** 被安抚的生物：UUID -> 记录（每 tick 压住 AI，到期移除） */
    private static final Map<UUID, Pacified> PACIFIED = new HashMap<>();
    private static final List<Wall> WALLS = new ArrayList<>();
    private static final List<Control> CONTROLS = new ArrayList<>();
    private static final List<Fantasy> FANTASIES = new ArrayList<>();

    // ==================================================================
    // 初阶：安抚 / 冲击 / 心灵之墙
    // ==================================================================

    /** 安抚：脚下白色圆圈，圈内生物（含敌对）变成友好生物 30 秒 */
    public static void pacify(SkillContext ctx) {
        spawnCircle(ctx, Kind.PACIFY, MagicCirclePayload.PACIFY);
    }

    /** 冲击：蓝色圆圈，圈内敌对生物双防 -10% 30 秒 */
    public static void impact(SkillContext ctx) {
        spawnCircle(ctx, Kind.IMPACT, MagicCirclePayload.IMPACT);
    }

    /** 心灵之墙：粒子墙围住准星生物，禁魔 + 魔法防御 -20% 30 秒 */
    public static void mindWall(SkillContext ctx) {
        LivingEntity target = ctx.targetEntity();
        if (target == null || target instanceof Player) {
            failCast(ctx, "心灵之墙需要锁定一个生物");
            return;
        }
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        beginTimed(ctx, WALL_TICKS);
        WALLS.add(new Wall(ctx.player(), level, target, target.position(), now + WALL_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(ctx.player()).rank())));
        MagicDamage.playSound(level, target.position(), SoundEvents.EVOKER_CAST_SPELL, 0.9F, 1.4F);
        drawParticleWall(level, target.position(), 24);
    }

    // ==================================================================
    // 中阶：恐惧 / 躁动 / 无畏
    // ==================================================================

    /** 恐惧：灰色圆圈，缓慢 + 失明 + 每秒 8 点心灵系伤害，30 秒 */
    public static void fear(SkillContext ctx) {
        spawnCircle(ctx, Kind.FEAR, MagicCirclePayload.FEAR);
    }

    /** 躁动：红色圆圈，每秒 10 点伤害 + 物理 -30% / 魔法 -50%，30 秒 */
    public static void agitation(SkillContext ctx) {
        spawnCircle(ctx, Kind.AGITATION, MagicCirclePayload.AGITATION);
    }

    /** 无畏：金色圆圈，友方加速/幸运 + 攻击 12 + 魔力 12 + 双防增益，30 秒 */
    public static void valor(SkillContext ctx) {
        spawnCircle(ctx, Kind.VALOR, MagicCirclePayload.VALOR);
    }

    /**
     * 5 个圆圈技能共用的生成逻辑。
     *
     * <p>圆圈由客户端渲染层贴身绘制（见 {@code PlayerFxLayer}），服务端只广播一次状态包；
     * 判定中心每 tick 取施法者当前位置，所以圈跟着玩家走，和看到的画面一致。</p>
     */
    private static void spawnCircle(SkillContext ctx, Kind kind, int variant) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 ground = GroundDecal.groundBelow(level, player.position(), 4);
        double radius = ctx.range();
        long now = level.getGameTime();
        beginTimed(ctx, CIRCLE_TICKS);
        CIRCLES.add(new Circle(kind, variant, player, level, radius, now + CIRCLE_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank()), now + 20L));
        MagicCircleSync.start(player, variant, CIRCLE_TICKS, (float) radius);
        // 展开瞬间沿圆周撒一圈系列色粒子（一次性，不是每 tick 同步）
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI * 2.0D / 32.0D;
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.MIND.color()),
                    ground.x + Math.cos(angle) * radius, ground.y + 0.15D, ground.z + Math.sin(angle) * radius,
                    2, 0.05D, 0.05D, 0.05D, 0.0D);
        }
        MagicDamage.playSound(level, ground, SoundEvents.BEACON_ACTIVATE, 0.7F, 1.3F);
    }

    // ==================================================================
    // 高阶：摄魄控心
    // ==================================================================

    /** 摄魄控心：红色符文连线控制敌对生物 40 秒 */
    public static void mindControl(SkillContext ctx) {
        LivingEntity target = ctx.targetEntity();
        if (target == null || target instanceof Player) {
            failCast(ctx, "摄魄控心需要锁定一个敌对生物");
            return;
        }
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        List<FxBillboard> sigils = new ArrayList<>(SIGIL_COUNT);
        for (int i = 0; i < SIGIL_COUNT; i++) {
            FxBillboard sigil = DisplayFx.spawn(level, player.position().add(0.0D, 1.2D, 0.0D),
                    MagicItems.MIND_SIGIL.get(), 0.8F, 0.8F, 0.8F, Display.BillboardConstraints.CENTER);
            if (sigil != null) {
                sigils.add(sigil);
            }
        }
        if (sigils.isEmpty()) {
            failCast(ctx, "摄魄控心生成失败");
            return;
        }
        long now = level.getGameTime();
        beginTimed(ctx, CONTROL_TICKS);
        CONTROLS.add(new Control(player, level, target, sigils, now + CONTROL_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        // 关键：控制期间停掉原版 AI 目标，否则生物每 tick 会自己重新寻路，把玩家的指令覆盖掉
        suppressAi(target);
        MagicDamage.playSound(level, target.position(), SoundEvents.EVOKER_CAST_SPELL, 1.0F, 0.7F);
    }

    /** 客户端按住 Alt 点击时发来的控制指令（只发动作，目标由服务端按玩家自己的视线算，防伪造） */
    public static void controlInput(ServerPlayer player, int action) {
        // 拘灵遣将（超阶）与摄魄控心共用 Alt+鼠标：灵契生效时让位给拘灵遣将，避免双响应
        if (CurseMagic.hasActivePact(player)) {
            return;
        }
        Control control = findControl(player);
        if (control == null || !control.target.isAlive() || control.target.isRemoved()) {
            return;
        }
        ServerLevel level = control.level;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double range = MagicData.get(player).rank().castRange();
        Vec3 end = eye.add(look.scale(range));
        HitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean missed = blockHit.getType() == HitResult.Type.MISS;
        Vec3 point = missed ? end : blockHit.getLocation();

        if (action == CONTROL_MOVE) {
            // 原来只从落点往下找 4 格，准星打远处/天空时目标悬在空中，寻路必然失败
            Vec3 stand = standPoint(level, point, missed);
            // 只有落点真正变了才重置寻路；按住右键连发时若每次都 nextPathTick=0，
            // 路径会被不停重建，生物原地打转，看起来就是「点了没反应」
            boolean changed = control.attackTarget != null
                    || control.moveTarget == null
                    || control.moveTarget.distanceToSqr(stand) > 0.36D;
            control.moveTarget = stand;
            control.attackTarget = null;
            if (changed) {
                control.nextPathTick = 0L;
                control.lastPathTarget = null;
                ping(level, stand, 18);
            }
        } else if (action == CONTROL_ATTACK) {
            LivingEntity victim = findEntityAlong(level, player, eye, point, control.target);
            if (victim == null) {
                // 射线没精确命中时，退而取准星落点附近的生物，避免「点了没反应」
                victim = nearestEntityNear(level, point, player, control.target, 3.5D);
            }
            if (victim == null) {
                // 没指到生物：只给一次反馈，绝不打断当前指令。
                // 原来这里会无条件把 moveTarget 也清空，连点几下空处后生物就永远没有指令了。
                ping(level, point, 8);
                return;
            }
            boolean changed = control.attackTarget != victim || control.moveTarget != null;
            control.attackTarget = victim;
            control.moveTarget = null;
            if (changed) {
                control.nextPathTick = 0L;
                control.lastPathTarget = null;
                control.nextAttackTick = 0L;
            }
            ping(level, victim.position().add(0.0D, victim.getBbHeight() * 0.6D, 0.0D), 20);
            if (changed && control.target instanceof Mob mob) {
                mob.getLookControl().setLookAt(victim, 30.0F, 30.0F);
            }
        }
    }

    /** 沿视线找最近的生物（排除施法者与被控生物本身） */
    private static @Nullable LivingEntity findEntityAlong(ServerLevel level, ServerPlayer player,
                                                          Vec3 eye, Vec3 stop, LivingEntity exclude) {
        AABB area = new AABB(eye, stop).inflate(1.0D);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (candidate == player || candidate == exclude || !candidate.isAlive() || candidate.isSpectator()) {
                continue;
            }
            var hit = candidate.getBoundingBox().inflate(0.4D).clip(eye, stop);
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

    /** 准星落点附近最近的活物（攻击指令的兜底目标） */
    private static @Nullable LivingEntity nearestEntityNear(ServerLevel level, Vec3 point,
                                                            ServerPlayer player, LivingEntity exclude,
                                                            double radius) {
        LivingEntity best = null;
        double bestDist = radius * radius;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(point, point).inflate(radius))) {
            if (candidate == player || candidate == exclude || !candidate.isAlive() || candidate.isSpectator()) {
                continue;
            }
            double dist = candidate.position().distanceToSqr(point);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    /** 把准星落点换成生物真正站得住的地面点 */
    private static Vec3 standPoint(ServerLevel level, Vec3 aim, boolean missed) {
        int x = Mth.floor(aim.x);
        int z = Mth.floor(aim.z);
        if (!missed) {
            BlockPos base = BlockPos.containing(aim);
            for (int i = 0; i <= 24; i++) {
                BlockPos floor = base.below(i);
                if (level.getBlockState(floor).isSolidRender()
                        && !level.getBlockState(floor.above()).isSolidRender()
                        && !level.getBlockState(floor.above(2)).isSolidRender()) {
                    return new Vec3(aim.x, floor.getY() + 1.02D, aim.z);
                }
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return new Vec3(aim.x, y + 0.02D, aim.z);
    }

    /** 指令反馈：落点画一圈红色符文粒子，玩家能直接看出「这次点击服务端收到了、落在哪」 */
    private static void ping(ServerLevel level, Vec3 pos, int count) {
        ColorParticleOption particle = ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFFFF3344);
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count;
            level.sendParticles(particle,
                    pos.x + Math.cos(angle) * 0.7D, pos.y + 0.12D, pos.z + Math.sin(angle) * 0.7D,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.sendParticles(particle, pos.x, pos.y + 0.10D, pos.z, 6, 0.25D, 0.20D, 0.25D, 0.02D);
    }

    // ==================================================================
    // 超阶：永恒幻想
    // ==================================================================

    /** 永恒幻想：玩家专属白金色结界 60 秒（客户端贴身绘制，跟随玩家） */
    public static void fantasy(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 ground = GroundDecal.groundBelow(level, player.position(), 4);
        long now = level.getGameTime();
        beginTimed(ctx, FANTASY_TICKS);
        PlayerMagicData data = MagicData.get(player);
        data.setMindFantasyActive(true);
        MagicSync.sync(player);
        FANTASIES.add(new Fantasy(player, level, now + FANTASY_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(data.rank())));
        MagicCircleSync.start(player, MagicCirclePayload.FANTASY, FANTASY_TICKS, FANTASY_RADIUS);
        MagicDamage.playSound(level, ground, SoundEvents.BEACON_ACTIVATE, 1.0F, 1.5F);
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2.0D / 24.0D;
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.MIND.color()),
                    ground.x + Math.cos(angle) * 2.4D, ground.y + 0.2D, ground.z + Math.sin(angle) * 2.4D,
                    3, 0.05D, 0.4D, 0.05D, 0.0D);
        }
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick 调用）
    // ==================================================================

    public static void tickWorld() {
        tickPacified();
        tickCircles();
        tickWalls();
        tickControls();
        tickFantasies();
    }

    /** 安抚：到期取消友好化，未到期每 tick 压住敌对 AI（防止反扑） */
    private static void tickPacified() {
        for (var iterator = PACIFIED.entrySet().iterator(); iterator.hasNext(); ) {
            Pacified pacified = iterator.next().getValue();
            LivingEntity entity = pacified.entity;
            if (!entity.isAlive() || entity.isRemoved()) {
                iterator.remove();
                continue;
            }
            long now = entity.level().getGameTime();
            if (now >= pacified.endTick) {
                iterator.remove();
                continue;
            }
            if (entity instanceof Mob mob) {
                mob.setTarget(null);
                mob.setAggressive(false);
            }
        }
    }

    private static void tickCircles() {
        for (int i = CIRCLES.size() - 1; i >= 0; i--) {
            Circle circle = CIRCLES.get(i);
            ServerLevel level = circle.level;
            long now = level.getGameTime();
            if (!circle.owner.isAlive() || now >= circle.endTick) {
                finishCircle(circle, now);
                CIRCLES.remove(i);
                continue;
            }
            // 圆圈跟着玩家走：判定中心每 tick 取施法者当前位置，与客户端画的圈一致
            Vec3 center = circle.owner.position();
            boolean dotTick = now >= circle.nextDotTick;
            if (dotTick) {
                circle.nextDotTick = now + 20L;
            }
            AABB area = new AABB(center, center)
                    .inflate(circle.radius, 8.0D, circle.radius);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (!entity.isAlive() || entity.isSpectator() || !inCircle(circle, entity)) {
                    continue;
                }
                // PVP 感知：其他玩家在 PVP 开启时算敌对目标（可被冲击/恐惧/躁动命中），
                // 关闭时仍算友方。VALOR 的增益不受影响，始终可给玩家加。
                boolean enemy = isHostile(circle.owner, entity);
                boolean friendly = !enemy;
                boolean ally = entity instanceof Player || friendly;
                switch (circle.kind) {
                    case PACIFY -> {
                        // 只友好化非玩家生物：不强行缴械其他玩家，避免 PVP 被初阶领域锁死
                        if (entity != circle.owner && !(entity instanceof Player)) {
                            pacifyEntity(circle, entity);
                        }
                    }
                    case IMPACT -> {
                        if (enemy && !isPacified(entity) && dotTick) {
                            spark(level, entity, 0.25D);
                        }
                    }
                    case FEAR -> {
                        if (enemy && !isPacified(entity)) {
                            entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, false));
                            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
                            if (dotTick) {
                                MagicDamage.magicDamage(circle.owner, entity, FEAR_DPS, MagicElement.MIND);
                                spark(level, entity, 0.5D);
                            }
                        }
                    }
                    case AGITATION -> {
                        if (enemy && !isPacified(entity) && dotTick) {
                            MagicDamage.magicDamage(circle.owner, entity, AGITATION_DPS, MagicElement.MIND);
                            spark(level, entity, 0.6D);
                        }
                    }
                    case VALOR -> {
                        if (ally) {
                            applyValor(circle, entity, now);
                        }
                    }
                }
            }
        }
    }

    /** 无畏：速度/幸运药水、攻击 +12、玩家魔力值 +12 */
    private static void applyValor(Circle circle, LivingEntity entity, long now) {
        if (now % 10L == 0L) {
            entity.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0, false, false));
            entity.addEffect(new MobEffectInstance(MobEffects.LUCK, 40, 0, false, false));
        }
        UUID uuid = entity.getUUID();
        // 首次进入或每 20 tick 刷新一次（多个无畏圈叠加时，其中一个结束也不会把加成卸没）
        if (!circle.buffed.containsKey(uuid) || now % 20L == 0L) {
            AttributeInstance attack = entity.getAttribute(Attributes.ATTACK_DAMAGE);
            if (attack != null) {
                attack.addOrUpdateTransientModifier(
                        new AttributeModifier(VALOR_ATTACK_ID, 12.0D, AttributeModifier.Operation.ADD_VALUE));
                circle.buffed.put(uuid, entity);
            }
        }
        if (entity instanceof ServerPlayer player) {
            MagicData.get(player).refreshMindValor();
        }
    }

    private static void tickWalls() {
        for (int i = WALLS.size() - 1; i >= 0; i--) {
            Wall wall = WALLS.get(i);
            ServerLevel level = wall.level;
            long now = level.getGameTime();
            if (!wall.owner.isAlive() || !wall.target.isAlive() || wall.target.isRemoved()
                    || now >= wall.endTick) {
                finishTimed(wall.owner, wall.skillId, wall.cooldownSeconds, now);
                WALLS.remove(i);
                continue;
            }
            if (now % 2L == 0L) {
                drawParticleWall(level, wall.center, 12);
            }
            if (wall.target instanceof Mob mob) {
                mob.setTarget(null);
            }
            // 粒子墙把生物困在中心 2 格内
            Vec3 pos = wall.target.position();
            double dx = pos.x - wall.center.x;
            double dz = pos.z - wall.center.z;
            if (dx * dx + dz * dz > WALL_RADIUS * WALL_RADIUS) {
                wall.target.setPos(wall.center.x, pos.y, wall.center.z);
                wall.target.setDeltaMovement(0.0D, Math.min(0.0D, wall.target.getDeltaMovement().y), 0.0D);
                wall.target.hurtMarked = true;
            }
        }
    }

    private static void tickControls() {
        for (int i = CONTROLS.size() - 1; i >= 0; i--) {
            Control control = CONTROLS.get(i);
            ServerLevel level = control.level;
            long now = level.getGameTime();
            if (!control.owner.isAlive()) {
                discardControl(control);
                CONTROLS.remove(i);
                continue;
            }
            // 生物被提前打死（左键控制生效后很常见）：技能立刻进冷却，但同样以爆裂收尾，
            // 否则玩家几乎永远看不到「最后的爆炸」
            if (!control.target.isAlive() || control.target.isRemoved()) {
                detonate(control, false);
                discardControl(control);
                finishTimed(control.owner, control.skillId, control.cooldownSeconds, now);
                CONTROLS.remove(i);
                continue;
            }
            // 40 秒到还活着：本体 300 爆炸伤害 + 3 格 150 溅射（不破坏地形）
            if (now >= control.endTick) {
                detonate(control, true);
                discardControl(control);
                finishTimed(control.owner, control.skillId, control.cooldownSeconds, now);
                CONTROLS.remove(i);
                continue;
            }
            // 每 tick 重新压住原版 AI（拴绳、受伤等原版逻辑会重新打开 MOVE/TARGET 标志），
            // 保证控制期间生物完全不执行自己的目标，只听下面的玩家指令。
            if (control.target instanceof Mob mob) {
                holdAi(mob);
            }
            LivingEntity victim = control.attackTarget;
            if (victim != null && (!victim.isAlive() || victim.isRemoved() || victim.level() != level)) {
                control.attackTarget = null;
                victim = null;
            }
            // 本 tick 的唯一目标点：null = 玩家没有指向 -> 原地不动
            Vec3 destination = null;
            double speed = 1.5D;
            if (victim != null) {
                double distance = flatDistance(control.target.position(), victim.position());
                if (distance > ATTACK_REACH * 0.85D) {
                    destination = victim.position();
                    speed = 1.8D;
                } else if (now >= control.nextAttackTick) {
                    // 已经贴身：原地挥击，不再继续推挤（原来会一边打一边抖）
                    performAttack(control, victim);
                    control.nextAttackTick = now + ATTACK_COOLDOWN;
                }
                if (control.target instanceof Mob mob) {
                    mob.getLookControl().setLookAt(victim, 30.0F, 30.0F);
                }
            } else if (control.moveTarget != null) {
                double distance = flatDistance(control.target.position(), control.moveTarget);
                if (distance <= 0.75D) {
                    // 走到点了，这条指令结束；之后没有新指令就原地不动
                    control.moveTarget = null;
                } else {
                    destination = control.moveTarget;
                }
            }
            if (control.target instanceof Mob mob) {
                if (destination != null) {
                    driveTo(control, mob, destination, speed, now);
                } else {
                    holdStill(mob);
                }
            } else if (destination != null) {
                // 非 Mob 生物：直接给水平速度
                Vec3 flat = destination.subtract(control.target.position());
                flat = new Vec3(flat.x, 0.0D, flat.z);
                if (flat.lengthSqr() > 1.0E-4D) {
                    Vec3 motion = flat.normalize().scale(0.35D);
                    control.target.setDeltaMovement(motion.x, control.target.getDeltaMovement().y, motion.z);
                    control.target.hurtMarked = true;
                }
            }
            updateSigils(control);
        }
    }

    private static void tickFantasies() {
        for (int i = FANTASIES.size() - 1; i >= 0; i--) {
            Fantasy fantasy = FANTASIES.get(i);
            ServerPlayer player = fantasy.owner;
            long now = fantasy.level.getGameTime();
            if (!player.isAlive() || now >= fantasy.endTick) {
                MagicData.get(player).setMindFantasyActive(false);
                MagicCircleSync.stop(player, MagicCirclePayload.FANTASY);
                finishTimed(player, fantasy.skillId, fantasy.cooldownSeconds, now);
                FANTASIES.remove(i);
                continue;
            }
            // 结界视觉由客户端渲染层贴身绘制，服务端只负责免疫与时长
            // 免疫中毒 / 失明（火焰/爆炸/岩浆/雷电在伤害事件里直接取消）
            player.removeEffect(MobEffects.POISON);
            player.removeEffect(MobEffects.BLINDNESS);
        }
    }

    // ==================================================================
    // 控制：移动 / 攻击 / 符文连线 / 爆炸
    // ==================================================================

    /** 水平距离：攻击/到位判定不看高度差，避免目标在高处时永远「够不着」 */
    private static double flatDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 控制期间每 tick 重新压住原版 AI：
     * 拴绳、受伤等原版逻辑会重新 enableControlFlag(MOVE/TARGET)，只在施法时禁用一次是不够的。
     * 同时清掉反击仇恨，避免被攻击的目标把被控生物拉回原版战斗 AI。
     */
    private static void holdAi(Mob mob) {
        for (Goal.Flag flag : Goal.Flag.values()) {
            mob.goalSelector.disableControlFlag(flag);
            mob.targetSelector.disableControlFlag(flag);
        }
        // 官方 cleanup 只会停掉「带被禁标志」的目标；少数不带任何 Flag 的原生目标
        // （例如个别生物的自定义漫游）仍会跑，这里补一刀，确保控制期间只剩玩家指令。
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning() && wrapped.getFlags().isEmpty()) {
                wrapped.stop();
            }
        }
        mob.setTarget(null);
        mob.setAggressive(false);
        mob.setLastHurtByMob(null);
    }

    /**
     * 有指令时每 tick 重新下 MoveControl 指令。
     * MoveControl.tick() 每 tick 都会把 operation 清成 WAIT，所以不能只在发包时设一次；
     * 寻路路径只在目标变化或每 10 tick 重算一次，避免每次点击/每 2 tick 都重建路径。
     */
    private static void driveTo(Control control, Mob mob, Vec3 destination, double speed, long now) {
        mob.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z, speed);
        if (control.lastPathTarget == null
                || control.lastPathTarget.distanceToSqr(destination) > 1.0D
                || now >= control.nextPathTick) {
            control.lastPathTarget = destination;
            control.nextPathTick = now + 10L;
            mob.getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
        }
        mob.getLookControl().setLookAt(destination.x, destination.y + 0.9D, destination.z);
    }

    /** 没有指令：清空寻路、AI 惯性与被击退的水平速度，严格原地不动（保留重力） */
    private static void holdStill(Mob mob) {
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
        mob.setSpeed(0.0F);
        mob.setXxa(0.0F);
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0D, velocity.y, 0.0D);
        mob.hurtMarked = true;
    }

    /** 控制开始：一次性停掉原版 AI 目标、当前路径与残余速度 */
    private static void suppressAi(LivingEntity target) {
        if (!(target instanceof Mob mob)) {
            return;
        }
        holdAi(mob);
        mob.getNavigation().stop();
        holdStill(mob);
    }

    /** 控制结束：把目标控制权还给生物，让它从静止状态恢复原有的自主移动 */
    private static void restoreAi(LivingEntity target) {
        if (!(target instanceof Mob mob)) {
            return;
        }
        for (Goal.Flag flag : Goal.Flag.values()) {
            mob.goalSelector.enableControlFlag(flag);
            mob.targetSelector.enableControlFlag(flag);
        }
        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.setSpeed(0.0F);
        mob.setXxa(0.0F);
    }

    private static void performAttack(Control control, LivingEntity victim) {
        // 直接 hurtServer 不走 MagicDamage，必须自己过 PVP 判定：PVP 关闭时不打其他玩家
        if (!com.cqgzs.cqlove.lan.PvpApi.canHurt(control.owner, victim)) {
            return;
        }
        LivingEntity attacker = control.target;
        AttributeInstance attack = attacker.getAttribute(Attributes.ATTACK_DAMAGE);
        double damage = Math.max(8.0D, (attack == null ? 4.0D : attack.getValue()) * 1.5D);
        victim.hurtServer(control.level, control.level.damageSources().mobAttack(attacker), (float) damage);
        attacker.swing(InteractionHand.MAIN_HAND);
        spark(control.level, victim, 1.2D);
        MagicDamage.playSound(control.level, victim.position(), SoundEvents.PLAYER_ATTACK_CRIT, 0.8F, 1.1F);
    }

    /** 红线：5 个红色符文在玩家与被控生物之间等距排布，每 tick 跟随两端 */
    private static void updateSigils(Control control) {
        Vec3 from = control.owner.getEyePosition().add(0.0D, 0.2D, 0.0D);
        Vec3 to = control.target.position().add(0.0D, control.target.getBbHeight() * 0.6D, 0.0D);
        int count = control.sigils.size();
        for (int i = 0; i < count; i++) {
            Vec3 pos = from.add(to.subtract(from).scale((i + 1.0D) / (count + 1.0D)));
            FxBillboard sigil = control.sigils.get(i);
            if (sigil.isAlive()) {
                sigil.setPos(pos.x, pos.y, pos.z);
            }
        }
        if (control.level.getGameTime() % 2L == 0L) {
            MagicDamage.line(control.level, from, to,
                    ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFFFF3344), 0.6D);
        }
    }

    /**
     * 技能收尾爆裂（不破坏地形）：
     * 40 秒到期且目标仍活着 -> 本体 300 爆炸伤害；目标被玩家提前打死 -> 不再打本体。
     * 两种情况都对 3 格内敌对生物造成 150 溅射，并播放完整爆炸特效。
     */
    private static void detonate(Control control, boolean hitTarget) {
        ServerLevel level = control.level;
        Vec3 center = control.target.position();
        double x = center.x;
        double y = center.y + Math.max(0.7D, control.target.getBbHeight() * 0.5D);
        double z = center.z;
        DamageSource source = level.damageSources().explosion(null, null);
        // 直接 hurtServer 不走 MagicDamage：PVP 关闭时不对其他玩家造成本体/溅射伤害
        if (hitTarget && control.target.isAlive()
                && com.cqgzs.cqlove.lan.PvpApi.canHurt(control.owner, control.target)) {
            control.target.hurtServer(level, source, (float) CONTROL_BURST);
        }
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(3.0D, 3.0D, 3.0D))) {
            if (entity != control.target && entity != control.owner && entity.isAlive()
                    && isHostile(control.owner, entity)
                    && com.cqgzs.cqlove.lan.PvpApi.canHurt(control.owner, entity)) {
                entity.hurtServer(level, source, (float) CONTROL_SPLASH);
            }
        }
        // 原来只有 POOF 白烟 + 小红点，视觉上完全不像爆炸；这里补真实爆炸粒子、
        // 火花、浓烟和一圈心灵系红色冲击波。
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 20, 1.4D, 0.9D, 1.4D, 0.12D);
        level.sendParticles(ParticleTypes.FLAME, x, y, z, 36, 1.3D, 0.8D, 1.3D, 0.09D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 20, 1.2D, 0.8D, 1.2D, 0.04D);
        ColorParticleOption mind = ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFFFF3344);
        level.sendParticles(mind, x, y, z, 80, 1.5D, 1.0D, 1.5D, 0.15D);
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2.0D / 24.0D;
            level.sendParticles(mind, x + Math.cos(angle) * 3.0D, center.y + 0.15D,
                    z + Math.sin(angle) * 3.0D, 2, 0.08D, 0.04D, 0.08D, 0.0D);
        }
        MagicDamage.playSound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 2.0F, 0.6F);
        MagicDamage.playSound(level, center, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0F, 0.5F);
    }

    private static void discardControl(Control control) {
        control.sigils.forEach(sigil -> sigil.discard());
        control.sigils.clear();
        restoreAi(control.target);
    }

    private static @Nullable Control findControl(ServerPlayer player) {
        for (Control control : CONTROLS) {
            if (control.owner == player) {
                return control;
            }
        }
        return null;
    }

    // ==================================================================
    // 对外查询（MagicEvents 调用）
    // ==================================================================

    /** 该玩家是否处在永恒幻想结界中（免疫/减伤统一走这里） */
    public static boolean hasFantasy(ServerPlayer player) {
        for (Fantasy fantasy : FANTASIES) {
            if (fantasy.owner == player) {
                return true;
            }
        }
        return false;
    }

    /** 永恒幻想免疫的伤害类型：火焰 / 爆炸 / 岩浆 / 雷电 */
    public static boolean isImmuneSource(DamageSource source) {
        return source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION)
                || source.is(DamageTypes.LAVA) || source.is(DamageTypes.ON_FIRE)
                || source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.HOT_FLOOR)
                || source.is(DamageTypes.LIGHTNING_BOLT);
    }

    /**
     * 心灵系全部防御增减的合成倍率（物理 / 魔法分别计算）。
     * 多个效果乘算叠加，和土系石盾、冰系冰棺的倍率一起在伤害事件里相乘。
     */
    public static float incomingMultiplier(LivingEntity entity, boolean magic) {
        double multiplier = 1.0D;
        for (Circle circle : CIRCLES) {
            if (circle.level != entity.level() || !inCircle(circle, entity)) {
                continue;
            }
            boolean enemy = isHostile(circle.owner, entity);
            boolean ally = entity instanceof Player || !enemy;
            switch (circle.kind) {
                case IMPACT -> {
                    if (enemy && !isPacified(entity)) {
                        multiplier *= IMPACT_MUL;
                    }
                }
                case AGITATION -> {
                    if (enemy && !isPacified(entity)) {
                        multiplier *= magic ? AGITATION_MAGIC : AGITATION_PHYS;
                    }
                }
                case VALOR -> {
                    if (ally) {
                        multiplier *= magic ? VALOR_MAGIC : VALOR_PHYS;
                    }
                }
                default -> {
                    // 安抚 / 恐惧只改 AI 与药水，不改防御
                }
            }
        }
        if (magic) {
            for (Wall wall : WALLS) {
                if (wall.target == entity) {
                    multiplier *= WALL_MAGIC;
                }
            }
        }
        for (Fantasy fantasy : FANTASIES) {
            if (fantasy.owner == entity) {
                multiplier *= magic ? FANTASY_MAGIC : FANTASY_PHYS;
            }
        }
        return (float) multiplier;
    }

    /** 被安抚的生物对任何人的攻击都被吞掉（已经"变成友好生物"） */
    public static boolean isPacifiedSource(DamageSource source) {
        LivingEntity attacker = attackerOf(source);
        return attacker != null && isPacified(attacker);
    }

    /** 心灵之墙内的生物无法释放魔法攻击：它造成的伤害（含弹射物/元素魔法）直接取消 */
    public static boolean isSilencedSource(DamageSource source) {
        LivingEntity attacker = attackerOf(source);
        if (attacker == null) {
            return false;
        }
        for (Wall wall : WALLS) {
            if (wall.target == attacker && attacker.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable LivingEntity attackerOf(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile projectile
                && projectile.getOwner() instanceof LivingEntity owner) {
            return owner;
        }
        if (direct instanceof LivingEntity living) {
            return living;
        }
        return source.getEntity() instanceof LivingEntity living ? living : null;
    }

    public static boolean isPacified(LivingEntity entity) {
        Pacified pacified = PACIFIED.get(entity.getUUID());
        return pacified != null && pacified.entity.isAlive();
    }

    /** 玩家下线/死亡：收掉他自己的全部心灵系效果 */
    public static void removePlayer(ServerPlayer player) {
        for (int i = CIRCLES.size() - 1; i >= 0; i--) {
            Circle circle = CIRCLES.get(i);
            if (circle.owner == player) {
                finishCircle(circle, circle.level.getGameTime());
                CIRCLES.remove(i);
            }
        }
        for (int i = WALLS.size() - 1; i >= 0; i--) {
            if (WALLS.get(i).owner == player) {
                WALLS.remove(i);
            }
        }
        for (int i = CONTROLS.size() - 1; i >= 0; i--) {
            Control control = CONTROLS.get(i);
            if (control.owner == player) {
                discardControl(control);
                CONTROLS.remove(i);
            }
        }
        for (int i = FANTASIES.size() - 1; i >= 0; i--) {
            if (FANTASIES.get(i).owner == player) {
                FANTASIES.remove(i);
            }
        }
        for (var iterator = PACIFIED.values().iterator(); iterator.hasNext(); ) {
            if (iterator.next().owner == player) {
                iterator.remove();
            }
        }
        // 一次性广播「该玩家全部圆圈清除」，不用给每个圈单独发包
        MagicCircleSync.clear(player);
        PlayerMagicData data = MagicData.get(player);
        data.setMindFantasyActive(false);
        data.clearMindValor();
    }

    /** 服务器关闭：全部收干净（客户端退出世界时会自己清缓存，这里只清服务端状态表） */
    public static void clearAll() {
        CIRCLES.forEach(Circle::clear);
        CIRCLES.clear();
        PACIFIED.clear();
        WALLS.clear();
        CONTROLS.forEach(MindMagic::discardControl);
        CONTROLS.clear();
        FANTASIES.clear();
        MagicCircleSync.clearAll();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static void pacifyEntity(Circle circle, LivingEntity entity) {
        UUID uuid = entity.getUUID();
        Pacified pacified = PACIFIED.get(uuid);
        if (pacified == null) {
            PACIFIED.put(uuid, new Pacified(circle.owner, entity, circle.endTick));
        } else {
            pacified.endTick = Math.max(pacified.endTick, circle.endTick);
        }
        if (entity instanceof Mob mob) {
            mob.setTarget(null);
            mob.setAggressive(false);
        }
    }

    private static void finishCircle(Circle circle, long now) {
        circle.clear();
        MagicCircleSync.stop(circle.owner, circle.variant);
        finishTimed(circle.owner, circle.skillId, circle.cooldownSeconds, now);
    }

    private static void drawParticleWall(ServerLevel level, Vec3 center, int density) {
        var particle = ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.MIND.color());
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2.0D / 12.0D;
            double x = center.x + Math.cos(angle) * WALL_RADIUS;
            double z = center.z + Math.sin(angle) * WALL_RADIUS;
            level.sendParticles(particle, x, center.y + 0.2D, z,
                    Math.max(1, density / 12), 0.02D, 1.3D, 0.02D, 0.0D);
        }
    }

    private static void spark(ServerLevel level, LivingEntity entity, double spread) {
        level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.MIND.color()),
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.6D, entity.getZ(),
                8, spread * 0.3D, spread * 0.4D, spread * 0.3D, 0.03D);
    }

    /** 圆圈跟随玩家：判定中心取施法者当前位置，和客户端画的圈完全一致 */
    private static boolean inCircle(Circle circle, LivingEntity entity) {
        Vec3 center = circle.owner.position();
        double dx = entity.getX() - center.x;
        double dz = entity.getZ() - center.z;
        return dx * dx + dz * dz <= circle.radius * circle.radius;
    }

    private static boolean isHostile(ServerPlayer owner, LivingEntity entity) {
        if (isPacified(entity)) {
            return false;
        }
        return CombatTracker.isEnemyTarget(owner, entity);
    }

    /** 持续技能统一开局：清掉预写冷却、锁定该技能到效果结束（超阶圆环由锁定自动延长） */
    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

    /** 持续技能统一收尾：效果结束 -> 写入完整冷却 + 收超阶圆环 */
    private static void finishTimed(ServerPlayer owner, String skillId, int cooldownSeconds, long now) {
        PlayerMagicData data = MagicData.get(owner);
        data.clearSkillLock(skillId);
        data.setCooldown(skillId, now + cooldownSeconds * 20L);
        data.endSuperRing(skillId);
        if (owner.connection != null) {
            MagicSync.sync(owner);
        }
    }

    private static void failCast(SkillContext ctx, String message) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(ctx.player());
        NoticeApi.send(ctx.player(), Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PlatformMods.MODID, path);
    }

    // ==================================================================
    // 内部类
    // ==================================================================

    private static final class Circle {
        final Kind kind;
        /** 客户端渲染用的样式编号，见 MagicCirclePayload */
        final int variant;
        final ServerPlayer owner;
        final ServerLevel level;
        final double radius;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        /** 挂过「攻击 +12」修饰符的生物，到期只卸掉没有别的无畏圈覆盖的 */
        final Map<UUID, LivingEntity> buffed = new HashMap<>();
        long nextDotTick;

        Circle(Kind kind, int variant, ServerPlayer owner, ServerLevel level, double radius,
               long endTick, String skillId, int cooldownSeconds, long nextDotTick) {
            this.kind = kind;
            this.variant = variant;
            this.owner = owner;
            this.level = level;
            this.radius = radius;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.nextDotTick = nextDotTick;
        }

        void clear() {
            // 圆圈是客户端渲染层，这里只需要卸掉属性修饰符
            for (Map.Entry<UUID, LivingEntity> entry : buffed.entrySet()) {
                boolean stillBuffed = false;
                for (Circle other : CIRCLES) {
                    if (other != this && other.kind == Kind.VALOR
                            && other.buffed.containsKey(entry.getKey())) {
                        stillBuffed = true;
                        break;
                    }
                }
                if (stillBuffed) {
                    continue;
                }
                LivingEntity entity = entry.getValue();
                if (!entity.isAlive()) {
                    continue;
                }
                AttributeInstance attack = entity.getAttribute(Attributes.ATTACK_DAMAGE);
                if (attack != null) {
                    attack.removeModifier(VALOR_ATTACK_ID);
                }
            }
            buffed.clear();
        }
    }

    private static final class Pacified {
        final ServerPlayer owner;
        final LivingEntity entity;
        long endTick;

        Pacified(ServerPlayer owner, LivingEntity entity, long endTick) {
            this.owner = owner;
            this.entity = entity;
            this.endTick = endTick;
        }
    }

    private static final class Wall {
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity target;
        final Vec3 center;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;

        Wall(ServerPlayer owner, ServerLevel level, LivingEntity target, Vec3 center, long endTick,
             String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.center = center;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    private static final class Control {
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity target;
        final List<FxBillboard> sigils;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        @Nullable Vec3 moveTarget;
        @Nullable LivingEntity attackTarget;
        /** 上次下发寻路的目标点，用于判断「同一个指令」而不要每 tick 重建路径 */
        @Nullable Vec3 lastPathTarget;
        long nextPathTick;
        long nextAttackTick;

        Control(ServerPlayer owner, ServerLevel level, LivingEntity target,
                List<FxBillboard> sigils, long endTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.sigils = sigils;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    private static final class Fantasy {
        final ServerPlayer owner;
        final ServerLevel level;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;

        Fantasy(ServerPlayer owner, ServerLevel level, long endTick,
                String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
}
