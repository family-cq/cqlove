package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.network.MagicCirclePayload;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 诅咒系 8 个技能（2026-11-16 设计实现）。
 *
 * <p>四阶沿用需求文档组名：恐惧之霾 / 邪蛛之阱 / 鬼刑 / 拘灵遣将，
 * 补成 初阶 3 + 中阶 3 + 高阶 1 + 超阶 1。定位为<b>削弱 + 持续伤害 + 灵体操控</b>：
 * 寒霜诅咒、虚弱压制、蚀魂 DOT、蛛网定身、放逐、灭魂，以及高阶鬼刑领域。</p>
 *
 * <ul>
 *   <li>初阶·恐惧之霾：寒（单体诅咒伤害 + 缓慢/虚弱）、压（落点范围压制 + 减伤）、
 *       伤（单体蚀魂 DOT 每秒 8 点，12 秒）。</li>
 *   <li>中阶·邪蛛之阱：魂蜘（落点蛛网定身 + DOT）、天涯（落点击退放逐 + 失明）、
 *       灭魂（单体即时 90 + 添加易伤）。</li>
 *   <li>高阶·鬼刑：跟随领域 30 秒，半径 = 施法范围，圈内敌对每秒 100 点诅咒伤害
 *       + 虚弱 + 缓慢 + 双防 -30%。</li>
 *   <li>超阶·拘灵遣将：60 秒灵契，按住 Alt —— 右键吸收准星生物（最多 12 只）、
 *       左键放出一只跟随玩家；放出的灵体会协同攻击玩家正在攻击的同一目标。</li>
 * </ul>
 *
 * <p>减防口径与其它系一致：在 MagicEvents 伤害事件里乘算，双防 -N% = 受伤 ×(1+N%)。</p>
 */
public final class CurseMagic {

    private CurseMagic() {
    }

    // ---------------- 时长（tick） ----------------
    private static final int CHILL_TICKS = 8 * 20;
    private static final int PRESS_TICKS = 12 * 20;
    private static final int WITHER_TICKS = 12 * 20;
    private static final int WEB_TICKS = 10 * 20;
    private static final int BLIND_TICKS = 5 * 20;
    private static final int GHOST_TICKS = 30 * 20;
    private static final int PACT_TICKS = 60 * 20;
    private static final int DOT_TICKS = 20;
    /** 契约灵体协同攻击间隔 */
    private static final int ALLY_ATTACK_COOLDOWN = 15;
    /** 灵契最多容纳的灵体数 */
    private static final int MAX_SPIRITS = 12;

    // ---------------- 伤害数值 ----------------
    private static final double CHILL_DAMAGE = 9.0D;
    private static final double PRESS_DAMAGE = 6.0D;
    private static final double WITHER_DPS = 8.0D;
    private static final double WEB_DPS = 12.0D;
    private static final double EXILE_DAMAGE = 15.0D;
    private static final double SOUL_SLAY = 90.0D;
    private static final double GHOST_DPS = 100.0D;
    /** 契约灵体每次攻击的伤害 */
    private static final double SPIRIT_ATTACK = 20.0D;

    /** 鬼刑领域圈内敌对双防 -30%（受伤 ×1.30） */
    private static final double GHOST_DEF = 1.30D;
    /** 范围（固定值） */
    private static final double PRESS_RADIUS = 5.0D;
    private static final double WEB_RADIUS = 5.0D;
    private static final double EXILE_RADIUS = 6.0D;

    /** 攻击力属性修饰器 id（契约灵体共享一个） */
    private static final Identifier SPIRIT_ATTACK_ID =
            Identifier.fromNamespaceAndPath("cqlove", "curse_spirit_attack");

    /** 客户端 -> 服务端控制指令 */
    public static final int CONTROL_ABSORB = 1; // Alt + 右键：吸收
    public static final int CONTROL_RELEASE = 2; // Alt + 左键：放出

    // ---------------- 运行时状态（不存档） ----------------
    private enum Kind { WITHER, WEB, GHOST }
    /** 单体/领域 DOT 状态 */
    private static final List<Dot> DOTS = new ArrayList<>();
    /** 高阶鬼刑领域 */
    private static final List<Ghost> GHOSTS = new ArrayList<>();
    /** 玩家的灵契（超阶拘灵遣将） */
    private static final List<Pact> PACTS = new ArrayList<>();
    /** 每 tick 待结算：施法者 -> (目标 -> 本 tick 累计伤害) */
    private static final Map<ServerPlayer, Map<LivingEntity, Double>> BATCH = new HashMap<>();

    // ==================================================================
    // 初阶·恐惧之霾
    // ==================================================================

    /** 寒：准星单体 9 点诅咒伤害 + 缓慢 II / 虚弱 I 8 秒 + 双防 -20% 8 秒 */
    public static void chill(SkillContext ctx) {
        ServerLevel level = ctx.level();
        LivingEntity target = resolveHostileTarget(ctx, 1.5D);
        if (target == null) {
            failCast(ctx, "恐惧之霾·寒失败: 准星没有指向敌对生物");
            return;
        }
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, curseParticle(), 0.3D);
        ctx.damage(target, CHILL_DAMAGE);
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CHILL_TICKS, 1));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CHILL_TICKS, 0));
        level.sendParticles(ParticleTypes.SNEEZE, hit.x, hit.y, hit.z,
                14, 0.4D, 0.5D, 0.4D, 0.05D);
        pulse(level, hit, 12);
        play(level, hit, SoundEvents.EVOKER_CAST_SPELL, 0.9F, 0.6F);
    }

    /** 压：落点半径 5 格，敌对 6 点 + 虚弱 II / 缓慢 I 12 秒（压制输出） */
    public static void press(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        for (LivingEntity entity : hostilesInRange(level, ctx.player(), center, PRESS_RADIUS)) {
            ctx.damage(entity, PRESS_DAMAGE);
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, PRESS_TICKS, 1));
            entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, PRESS_TICKS, 0));
        }
        ringParticles(level, center, PRESS_RADIUS, 24);
        play(level, center, SoundEvents.EVOKER_CAST_SPELL, 1.0F, 0.5F);
    }

    /** 伤：准星单体蚀魂 12 秒，每秒 8 点诅咒伤害 */
    public static void wither(SkillContext ctx) {
        ServerLevel level = ctx.level();
        LivingEntity target = resolveHostileTarget(ctx, 1.5D);
        if (target == null) {
            failCast(ctx, "恐惧之霾·伤失败: 准星没有指向敌对生物");
            return;
        }
        long now = level.getGameTime();
        DOTS.removeIf(dot -> dot.target == target && dot.kind == Kind.WITHER);
        DOTS.add(new Dot(Kind.WITHER, ctx.player(), level, target,
                now + WITHER_TICKS, now + DOT_TICKS, WITHER_DPS));
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, curseParticle(), 0.3D);
        pulse(level, hit, 16);
        play(level, hit, SoundEvents.BREWING_STAND_BREW, 0.9F, 0.6F);
    }

    // ==================================================================
    // 中阶·邪蛛之阱
    // ==================================================================

    /** 魂蜘：落点半径 5 格蛛网 10 秒，敌对定身 + 每秒 12 点诅咒伤害 */
    public static void spider(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        long now = level.getGameTime();
        DOTS.removeIf(dot -> dot.kind == Kind.WEB && flatDistance(dot.center, center) < 0.5D);
        DOTS.add(new Dot(Kind.WEB, ctx.player(), level, null,
                now + WEB_TICKS, now + DOT_TICKS, WEB_DPS, center, WEB_RADIUS));
        ringParticles(level, center, WEB_RADIUS, 28);
        pulse(level, center.add(0.0D, 0.8D, 0.0D), 20);
        play(level, center, SoundEvents.EVOKER_CAST_SPELL, 0.8F, 0.5F);
    }

    /** 天涯：落点半径 6 格，敌对 15 点 + 强力击退放逐 + 失明 5 秒 */
    public static void exile(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        for (LivingEntity entity : hostilesInRange(level, ctx.player(), center, EXILE_RADIUS)) {
            ctx.damage(entity, EXILE_DAMAGE);
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS, 0));
            knockbackAway(center, entity, 2.2D);
        }
        ringParticles(level, center, EXILE_RADIUS, 30);
        play(level, center, SoundEvents.BREEZE_SHOOT, 1.1F, 0.6F);
    }

    /** 灭魂：准星单体 90 点诅咒伤害 + 30 秒双防 -30%（易伤） */
    public static void soulSlay(SkillContext ctx) {
        ServerLevel level = ctx.level();
        LivingEntity target = resolveHostileTarget(ctx, 1.5D);
        if (target == null) {
            failCast(ctx, "邪蛛之阱·灭魂失败: 准星没有指向敌对生物");
            return;
        }
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, curseParticle(), 0.3D);
        ctx.damage(target, SOUL_SLAY);
        // 易伤用一条短 DOT 承载（不额外扣血），由 incomingMultiplier 读取
        long now = level.getGameTime();
        DOTS.removeIf(dot -> dot.target == target && dot.kind == Kind.GHOST && dot.vulnerable);
        DOTS.add(new Dot(Kind.GHOST, ctx.player(), level, target,
                now + (30 * 20), Long.MAX_VALUE, 0.0D, null, 0.0D, true));
        level.sendParticles(ParticleTypes.EXPLOSION, hit.x, hit.y, hit.z,
                6, 0.6D, 0.6D, 0.6D, 0.05D);
        pulse(level, hit, 28);
        play(level, hit, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.1F, 0.5F);
    }

    // ==================================================================
    // 高阶 / 超阶
    // ==================================================================

    /** 鬼刑：跟随领域 30 秒，半径 = 施法范围，圈内敌对每秒 100 点 + 虚弱/缓慢 + 双防 -30% */
    public static void ghostPunish(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        float radius = (float) ctx.range();
        beginTimed(ctx, GHOST_TICKS);
        @Nullable FxBillboard display = DisplayFx.spawn(level,
                player.position().add(0.0D, 2.7D, 0.0D), MagicItems.CURSE_GHOST.get(),
                1.3F, 1.3F, 1.3F, Display.BillboardConstraints.CENTER);
        GHOSTS.add(new Ghost(player, level, radius, display, now + GHOST_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        MagicCircleSync.start(player, MagicCirclePayload.CURSE_GHOST, GHOST_TICKS, radius);
        Vec3 at = player.position().add(0.0D, 0.15D, 0.0D);
        ringParticles(level, at, radius, 32);
        pulse(level, at.add(0.0D, 1.0D, 0.0D), 30);
        play(level, at, SoundEvents.EVOKER_CAST_SPELL, 1.2F, 0.5F);
        play(level, at, SoundEvents.BELL_RESONATE, 1.0F, 0.7F);
    }

    /**
     * 拘灵遣将：60 秒灵契。按住 Alt —— 右键吸收准星生物（最多 12 只），左键放出一只跟随玩家；
     * 放出的灵体协同攻击玩家正在攻击的同一目标（见 {@link #notifyPlayerAttack}）。
     */
    public static void soulPact(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        beginTimed(ctx, PACT_TICKS);
        @Nullable FxBillboard display = DisplayFx.spawn(level,
                player.position().add(0.0D, 2.7D, 0.0D), MagicItems.CURSE_PACT.get(),
                1.3F, 1.3F, 1.3F, Display.BillboardConstraints.CENTER);
        PACTS.add(new Pact(player, level, display, now + PACT_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        MagicCircleSync.start(player, MagicCirclePayload.CURSE_PACT, PACT_TICKS,
                (float) Math.min(ctx.range(), 8.0D));
        Vec3 at = player.position().add(0.0D, 0.15D, 0.0D);
        ringParticles(level, at, 6.0D, 32);
        play(level, at, SoundEvents.EVOKER_CAST_SPELL, 1.2F, 0.5F);
        play(level, at, SoundEvents.TOTEM_USE, 1.0F, 0.9F);
        NoticeApi.send(player, Component.literal("拘灵遣将: 按住 Alt + 右键吸收生物, 左键放出协同作战 (最多 12 只)")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    // ==================================================================
    // 拘灵遣将：客户端控制指令（只带动作，目标由服务端按玩家视线算）
    // ==================================================================

    public static void controlInput(ServerPlayer player, int action) {
        Pact pact = findPact(player);
        if (pact == null) {
            return;
        }
        ServerLevel level = pact.level;
        if (action == CONTROL_ABSORB) {
            absorb(pact, level);
        } else if (action == CONTROL_RELEASE) {
            release(pact, level);
        }
    }

    /** Alt + 右键：吸收准星生物（敌对生物或中立生物，不能是玩家/自己/已契约的） */
    private static void absorb(Pact pact, ServerLevel level) {
        ServerPlayer owner = pact.owner;
        int total = pact.stored.size() + pact.released.size();
        if (total >= MAX_SPIRITS) {
            NoticeApi.send(owner, Component.literal("灵契已满 (12 只)")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        LivingEntity target = entityAlongLook(owner, pact.level, Math.min(MagicData.get(owner).rank().castRange(), 16.0D));
        if (target == null || target instanceof Player || target == owner
                || pact.stored.contains(target) || pact.released.contains(target)) {
            return;
        }
        // 不移除实体（26.1 里已移除的实体无法重新加回世界），改为隐身 + 冻结 + 无敌，
        // 存在灵契里；放出时恢复可见并接管 AI。
        suppressAi(target);
        target.setInvisible(true);
        target.setInvulnerable(true);
        target.setNoGravity(true);
        target.setDeltaMovement(Vec3.ZERO);
        // 关键：关掉物理碰撞。noPhysics=true 后实体之间（含玩家）不再互相推挤，
        // 解决"吸收后生物把玩家推着走、失控"的问题。
        target.noPhysics = true;
        pact.stored.add(target);
        Vec3 at = owner.position().add(0.0D, 1.0D, 0.0D);
        level.sendParticles(curseParticle(), at.x, at.y, at.z,
                24, 0.5D, 1.0D, 0.5D, 0.05D);
        play(level, at, SoundEvents.EVOKER_CAST_SPELL, 0.9F, 0.6F);
        NoticeApi.send(owner, Component.literal("已拘灵: " + target.getName().getString()
                + " (" + (pact.stored.size() + pact.released.size()) + "/" + MAX_SPIRITS + ")")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** Alt + 左键：放出一只灵体；若准星指向生物，把它设为灵体们协同攻击的共同目标 */
    private static void release(Pact pact, ServerLevel level) {
        ServerPlayer owner = pact.owner;
        if (!pact.stored.isEmpty()) {
            LivingEntity spirit = pact.stored.remove(pact.stored.size() - 1);
            // 实体一直在世界里（吸收时只是隐身冻结），这里恢复可见并放到玩家旁边
            Vec3 at = owner.position().add(owner.getLookAngle().scale(1.6D)).add(0.0D, 0.1D, 0.0D);
            spirit.setPos(at.x, at.y, at.z);
            spirit.setInvisible(false);
            spirit.setInvulnerable(false);
            spirit.setNoGravity(false);
            spirit.noPhysics = false; // 放出后恢复物理（跟随/攻击需要正常碰撞与重力）
            spirit.setHealth(spirit.getMaxHealth());
            spirit.removeAllEffects();
            suppressAi(spirit);
            // 统一属性 API：用该生物自身当前属性生成 10 项快照（魔力=攻击力等）
            SummonStats.generate(spirit);
            pact.released.add(spirit);
            play(level, at, SoundEvents.EVOKER_CAST_SPELL, 1.0F, 1.3F);
        }
        // 左键同时指定协同攻击目标
        LivingEntity target = entityAlongLook(owner, level, Math.min(MagicData.get(owner).rank().castRange(), 20.0D));
        if (target != null && target != owner && target.isAlive()
                && !pact.released.contains(target)) {
            pact.sharedTarget = target;
            NoticeApi.send(owner, Component.literal("灵体目标: " + target.getName().getString())
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }
    }

    /**
     * 玩家造成伤害时由 MagicEvents 调用，把目标登记为所有契约灵体的协同攻击目标。
     * 这样"玩家攻击同一目标"时灵体自动一起打（近战/技能/弹射物都走 LivingDamageEvent.Post）。
     */
    public static void notifyPlayerAttack(ServerPlayer attacker, LivingEntity victim) {
        if (victim == null || victim == attacker) {
            return;
        }
        Pact pact = findPact(attacker);
        if (pact != null) {
            pact.sharedTarget = victim;
        }
    }

    private static @Nullable Pact findPact(ServerPlayer player) {
        for (Pact pact : PACTS) {
            if (pact.owner == player) {
                return pact;
            }
        }
        return null;
    }

    /** 该玩家是否有生效中的灵契（摄魄控心输入据此让位，避免 Alt+鼠标双系统同时响应） */
    public static boolean hasActivePact(ServerPlayer player) {
        return findPact(player) != null;
    }

    /** 沿视线找可吸收/可指定目标的生物（不判断敌对：吸收中立生物也允许） */
    private static @Nullable LivingEntity entityAlongLook(ServerPlayer player, ServerLevel level, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 end = eye.add(look.scale(range));
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(eye, end).inflate(1.2D))) {
            if (entity == player || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            var hit = entity.getBoundingBox().inflate(0.5D).clip(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double dist = hit.get().distanceToSqr(eye);
            if (dist < bestDist) {
                bestDist = dist;
                best = entity;
            }
        }
        return best;
    }

    // ==================================================================
    // 每 tick 驱动
    // ==================================================================

    public static void tickWorld() {
        BATCH.clear();
        long now = -1L;

        // DOT（单体 + 蛛网范围）
        for (int i = DOTS.size() - 1; i >= 0; i--) {
            Dot dot = DOTS.get(i);
            now = dot.level.getGameTime();
            if (now >= dot.endTick) {
                DOTS.remove(i);
                continue;
            }
            if (dot.nextPulseTick == Long.MAX_VALUE) {
                continue; // 灭魂易伤标记：只用于减防，不结算伤害
            }
            if (now >= dot.nextPulseTick) {
                dot.nextPulseTick = now + DOT_TICKS;
                if (dot.kind == Kind.WEB) {
                    for (LivingEntity entity : hostilesInRange(dot.level, dot.owner, dot.center, dot.radius)) {
                        addDamage(dot.owner, entity, dot.dps);
                        if (entity instanceof Mob mob) {
                            holdStill(mob); // 蛛网定身
                        }
                    }
                    if (now % 40L == 0L) {
                        ringParticles(dot.level, dot.center, dot.radius, 16);
                    }
                } else if (dot.target != null && dot.target.isAlive()) {
                    addDamage(dot.owner, dot.target, dot.dps);
                    pulse(dot.level, dot.target.position().add(0.0D, dot.target.getBbHeight() * 0.5D, 0.0D), 5);
                }
            }
        }

        // 鬼刑领域
        for (int i = GHOSTS.size() - 1; i >= 0; i--) {
            Ghost ghost = GHOSTS.get(i);
            ServerLevel level = ghost.level;
            ServerPlayer owner = ghost.owner;
            now = level.getGameTime();
            if (!owner.isAlive() || now >= ghost.endTick) {
                discardGhost(ghost);
                finishTimed(owner, ghost.skillId, ghost.cooldownSeconds, now);
                GHOSTS.remove(i);
                continue;
            }
            if (ghost.display != null && ghost.display.isAlive()) {
                Vec3 at = owner.position().add(0.0D, 2.7D, 0.0D);
                ghost.display.setPos(at.x, at.y + Math.sin(now * 0.05D) * 0.12D, at.z);
            }
            for (LivingEntity entity : hostilesInRange(level, owner, owner.position(), ghost.radius)) {
                addDamage(owner, entity, GHOST_DPS);
                entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1));
                entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
            }
            if (now % 40L == 0L) {
                ringParticles(level, owner.position(), ghost.radius, 20);
            }
        }

        // 灵契：放出灵体跟随 + 协同攻击 + 到期归还
        for (int i = PACTS.size() - 1; i >= 0; i--) {
            Pact pact = PACTS.get(i);
            ServerLevel level = pact.level;
            ServerPlayer owner = pact.owner;
            now = level.getGameTime();
            if (!owner.isAlive() || now >= pact.endTick) {
                releaseAll(pact);
                finishTimed(owner, pact.skillId, pact.cooldownSeconds, now);
                PACTS.remove(i);
                continue;
            }
            if (pact.display != null && pact.display.isAlive()) {
                Vec3 at = owner.position().add(0.0D, 2.7D, 0.0D);
                pact.display.setPos(at.x, at.y + Math.sin(now * 0.05D) * 0.12D, at.z);
            }
            if (pact.sharedTarget != null
                    && (!pact.sharedTarget.isAlive() || pact.sharedTarget.isRemoved()
                        || pact.sharedTarget.level() != level)) {
                pact.sharedTarget = null;
            }
            // 封存的灵体：保持隐身 + 冻结在原地（noPhysics 已开，不会推挤玩家）。
            // 注意：绝不能把它们传送到玩家身上——多个实体与玩家重叠会被原版碰撞把玩家推着走。
            for (LivingEntity stored : pact.stored) {
                if (!stored.isAlive()) {
                    continue;
                }
                stored.setDeltaMovement(Vec3.ZERO);
            }
            int index = 0;
            for (LivingEntity spirit : pact.released) {
                if (!spirit.isAlive() || spirit.isRemoved()) {
                    continue;
                }
                if (spirit instanceof Mob mob) {
                    holdAi(mob);
                }
                LivingEntity target = pact.sharedTarget;
                Vec3 destination = null;
                double speed = 1.6D;
                if (target != null) {
                    double distance = flatDistance(spirit.position(), target.position());
                    if (distance > 2.4D) {
                        destination = target.position();
                        speed = 1.9D;
                    } else if (now >= pact.nextAttackTick) {
                        // 协同攻击（走 PVP 判定）
                        if (com.cqgzs.cqlove.lan.PvpApi.canHurt(owner, target)) {
                            spirit.hurtServer(level, level.damageSources().mobAttack(spirit), (float) SPIRIT_ATTACK);
                            spirit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        }
                        pact.nextAttackTick = now + ALLY_ATTACK_COOLDOWN;
                    }
                    if (spirit instanceof Mob mob) {
                        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
                    }
                } else {
                    // 无目标：绕玩家环绕跟随
                    double angle = now * 0.03D + index * Math.PI * 2.0D / Math.max(1, pact.released.size());
                    destination = owner.position().add(Math.cos(angle) * 2.0D, 0.0D, Math.sin(angle) * 2.0D);
                    if (flatDistance(spirit.position(), owner.position()) < 2.6D) {
                        destination = null;
                    }
                }
                if (spirit instanceof Mob mob) {
                    if (destination != null) {
                        driveTo(mob, destination, speed, pact, now);
                    } else {
                        holdStill(mob);
                    }
                }
                index++;
            }
        }

        flush();
    }

    private static void addDamage(ServerPlayer owner, LivingEntity target, double base) {
        if (owner == null || target == null || target == owner || !target.isAlive() || target.isRemoved()) {
            return;
        }
        BATCH.computeIfAbsent(owner, key -> new HashMap<>())
                .merge(target, base, Double::sum);
    }

    private static void flush() {
        for (Map.Entry<ServerPlayer, Map<LivingEntity, Double>> caster : BATCH.entrySet()) {
            ServerPlayer owner = caster.getKey();
            for (Map.Entry<LivingEntity, Double> hit : caster.getValue().entrySet()) {
                LivingEntity target = hit.getKey();
                if (target == owner || !target.isAlive() || target.isRemoved()) {
                    continue;
                }
                MagicDamage.magicDamage(owner, target, hit.getValue(), MagicElement.CURSE);
            }
        }
    }

    // ==================================================================
    // 伤害事件：减防
    // ==================================================================

    /** 与其它系乘算叠加：鬼刑领域 / 灭魂易伤使圈内敌对双防 -30% */
    public static float incomingMultiplier(LivingEntity entity) {
        double multiplier = 1.0D;
        for (Dot dot : DOTS) {
            if (dot.vulnerable && dot.target == entity) {
                multiplier *= GHOST_DEF;
            }
        }
        for (Ghost ghost : GHOSTS) {
            if (ghost.owner != entity && hostilesInPlayerRange(ghost, entity)) {
                multiplier *= GHOST_DEF;
            }
        }
        return (float) multiplier;
    }

    private static boolean hostilesInPlayerRange(Ghost ghost, LivingEntity entity) {
        return entity.isAlive() && entity.level() == ghost.level
                && CombatTracker.isEnemyTarget(ghost.owner, entity)
                && within(ghost.owner.position(), entity.position(), ghost.radius);
    }

    // ==================================================================
    // 清理
    // ==================================================================

    public static void removePlayer(ServerPlayer player) {
        DOTS.removeIf(dot -> dot.owner == player || dot.target == player);
        for (int i = GHOSTS.size() - 1; i >= 0; i--) {
            if (GHOSTS.get(i).owner == player) {
                discardGhost(GHOSTS.remove(i));
            }
        }
        for (int i = PACTS.size() - 1; i >= 0; i--) {
            if (PACTS.get(i).owner == player) {
                releaseAll(PACTS.remove(i));
            }
        }
        MagicCircleSync.stop(player, MagicCirclePayload.CURSE_GHOST);
        MagicCircleSync.stop(player, MagicCirclePayload.CURSE_PACT);
    }

    public static void clearAll() {
        DOTS.clear();
        for (Ghost ghost : GHOSTS) {
            if (ghost.display != null) {
                ghost.display.discard();
            }
        }
        GHOSTS.clear();
        for (Pact pact : PACTS) {
            discardPactEntities(pact);
        }
        PACTS.clear();
        BATCH.clear();
    }

    private static void discardGhost(Ghost ghost) {
        if (ghost.display != null) {
            ghost.display.discard();
        }
        MagicCircleSync.stop(ghost.owner, MagicCirclePayload.CURSE_GHOST);
    }

    /** 灵契结束：隐身存起来的灵体原地现身并归还 AI（实体始终在世界里，无需重新添加） */
    private static void releaseAll(Pact pact) {
        for (LivingEntity spirit : pact.stored) {
            returnSpirit(spirit, pact.owner);
        }
        for (LivingEntity spirit : pact.released) {
            if (spirit.isAlive()) {
                restoreAi(spirit);
            }
        }
        pact.stored.clear();
        pact.released.clear();
        pact.sharedTarget = null;
        if (pact.display != null) {
            pact.display.discard();
        }
        MagicCircleSync.stop(pact.owner, MagicCirclePayload.CURSE_PACT);
    }

    private static void discardPactEntities(Pact pact) {
        for (LivingEntity spirit : pact.stored) {
            returnSpirit(spirit, pact.owner);
        }
        for (LivingEntity spirit : pact.released) {
            if (spirit.isAlive()) {
                restoreAi(spirit);
            }
        }
        if (pact.display != null) {
            pact.display.discard();
        }
    }

    /** 归还一只被封存灵体：恢复可见 / 重力 / AI，原地放下 */
    private static void returnSpirit(LivingEntity spirit, ServerPlayer owner) {
        if (spirit.isAlive()) {
            spirit.setInvisible(false);
            spirit.setInvulnerable(false);
            spirit.setNoGravity(false);
            spirit.noPhysics = false;
            restoreAi(spirit);
        }
    }

    // ==================================================================
    // AI 操控（参考摄魄控心）
    // ==================================================================

    private static void suppressAi(LivingEntity entity) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        holdAi(mob);
        mob.getNavigation().stop();
        holdStill(mob);
    }

    private static void restoreAi(LivingEntity entity) {
        if (!(entity instanceof Mob mob)) {
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

    private static void holdAi(Mob mob) {
        for (Goal.Flag flag : Goal.Flag.values()) {
            mob.goalSelector.disableControlFlag(flag);
            mob.targetSelector.disableControlFlag(flag);
        }
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning() && wrapped.getFlags().isEmpty()) {
                wrapped.stop();
            }
        }
        mob.setTarget(null);
        mob.setAggressive(false);
        mob.setLastHurtByMob(null);
    }

    private static void driveTo(Mob mob, Vec3 destination, double speed, Pact pact, long now) {
        mob.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z, speed);
        if (pact.lastPathTarget == null
                || pact.lastPathTarget.distanceToSqr(destination) > 1.0D
                || now >= pact.nextPathTick) {
            pact.lastPathTarget = destination;
            pact.nextPathTick = now + 10L;
            mob.getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
        }
        mob.getLookControl().setLookAt(destination.x, destination.y + 0.9D, destination.z);
    }

    private static void holdStill(Mob mob) {
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
        mob.setSpeed(0.0F);
        mob.setXxa(0.0F);
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0D, velocity.y, 0.0D);
        mob.hurtMarked = true;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static @Nullable LivingEntity resolveHostileTarget(SkillContext ctx, double fallbackRadius) {
        LivingEntity target = ctx.targetEntity();
        if (target != null && target.isAlive() && !target.isSpectator()
                && target != ctx.player() && hostile(ctx.player(), target)) {
            return target;
        }
        return nearestHostile(ctx.level(), ctx.player(), ctx.targetPos(), fallbackRadius);
    }

    private static @Nullable LivingEntity nearestHostile(ServerLevel level, ServerPlayer owner,
                                                          Vec3 center, double radius) {
        LivingEntity best = null;
        double bestDist = radius * radius;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 5.0D, radius))) {
            if (!entity.isAlive() || entity.isSpectator() || entity == owner
                    || !hostile(owner, entity)) {
                continue;
            }
            double dist = flatDistance(center, entity.position());
            if (dist < bestDist) {
                bestDist = dist;
                best = entity;
            }
        }
        return best;
    }

    private static List<LivingEntity> hostilesInRange(ServerLevel level, ServerPlayer owner,
                                                       Vec3 center, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            if (!entity.isAlive() || entity.isSpectator() || entity == owner
                    || !hostile(owner, entity) || !within(center, entity.position(), radius)) {
                continue;
            }
            result.add(entity);
        }
        return result;
    }

    private static boolean hostile(ServerPlayer owner, LivingEntity entity) {
        return CombatTracker.isEnemyTarget(owner, entity);
    }

    private static boolean within(Vec3 center, Vec3 pos, double radius) {
        return flatDistance(center, pos) <= radius;
    }

    private static double flatDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void knockbackAway(Vec3 origin, LivingEntity target, double strength) {
        double dx = target.getX() - origin.x;
        double dz = target.getZ() - origin.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            dx = 1.0D;
            dz = 0.0D;
            len = 1.0D;
        }
        Vec3 push = new Vec3(dx / len * strength, 0.3D, dz / len * strength);
        target.setDeltaMovement(target.getDeltaMovement().add(push));
    }

    private static ColorParticleOption curseParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.CURSE.color());
    }

    private static void pulse(ServerLevel level, Vec3 pos, int count) {
        level.sendParticles(curseParticle(), pos.x, pos.y, pos.z,
                count, 0.35D, 0.5D, 0.35D, 0.04D);
    }

    private static void ringParticles(ServerLevel level, Vec3 center, double radius, int count) {
        double phase = level.getGameTime() * 0.05D;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count + phase;
            level.sendParticles(curseParticle(),
                    center.x + Math.cos(angle) * radius, center.y + 0.18D,
                    center.z + Math.sin(angle) * radius,
                    2, 0.04D, 0.2D, 0.04D, 0.0D);
        }
    }

    private static void play(ServerLevel level, Vec3 pos, net.minecraft.sounds.SoundEvent sound,
                             float volume, float pitch) {
        MagicDamage.playSound(level, pos, sound, volume, pitch);
    }

    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

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

    // ---------------- 状态记录 ----------------

    private static final class Dot {
        final Kind kind;
        final ServerPlayer owner;
        final ServerLevel level;
        final @Nullable LivingEntity target;
        final long endTick;
        final double dps;
        final @Nullable Vec3 center;
        final double radius;
        final boolean vulnerable;
        long nextPulseTick;

        Dot(Kind kind, ServerPlayer owner, ServerLevel level, @Nullable LivingEntity target,
            long endTick, long nextPulseTick, double dps) {
            this(kind, owner, level, target, endTick, nextPulseTick, dps, null, 0.0D, false);
        }

        Dot(Kind kind, ServerPlayer owner, ServerLevel level, @Nullable LivingEntity target,
            long endTick, long nextPulseTick, double dps, @Nullable Vec3 center, double radius) {
            this(kind, owner, level, target, endTick, nextPulseTick, dps, center, radius, false);
        }

        Dot(Kind kind, ServerPlayer owner, ServerLevel level, @Nullable LivingEntity target,
            long endTick, long nextPulseTick, double dps, @Nullable Vec3 center, double radius,
            boolean vulnerable) {
            this.kind = kind;
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.dps = dps;
            this.center = center;
            this.radius = radius;
            this.vulnerable = vulnerable;
        }
    }

    private static final class Ghost {
        final ServerPlayer owner;
        final ServerLevel level;
        final float radius;
        final @Nullable FxBillboard display;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;

        Ghost(ServerPlayer owner, ServerLevel level, float radius,
              @Nullable FxBillboard display, long endTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.radius = radius;
            this.display = display;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    private static final class Pact {
        final ServerPlayer owner;
        final ServerLevel level;
        final @Nullable FxBillboard display;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        final List<LivingEntity> stored = new ArrayList<>();
        final List<LivingEntity> released = new ArrayList<>();
        @Nullable LivingEntity sharedTarget;
        long nextAttackTick;
        long nextPathTick;
        Vec3 lastPathTarget;

        Pact(ServerPlayer owner, ServerLevel level, @Nullable FxBillboard display,
             long endTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
}
