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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.network.MagicCirclePayload;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 音系 8 个技能（2026-11-16 设计实现）。
 *
 * <p>四阶沿用需求文档组名：音弦 / 杀乐 / 断葬曲 / 曲终帝陨，
 * 补成 初阶 3 + 中阶 3 + 高阶 1 + 超阶 1。定位为<b>声波攻击 + 控场</b>。</p>
 *
 * <p><b>2026-11-16 调整：全系技能改为范围性 + 穿透性</b>——直线技能沿视线穿透途中所有敌对、
 * 不设命中上限；落点技能按半径命中范围内全部敌对；领域技能覆盖整个圈。
 * 数值：杀 3 / 扰 6 / 探 9 / 弦 30（未指定，沿用）/ 弓 60 / 爆 90 /
 * 断葬曲每秒 100 / 曲终帝陨每秒 500。</p>
 *
 * <ul>
 *   <li>初阶·音弦：杀（穿透音刃 3 点 + 失明 3 秒）、扰（半径 4 震荡 6 点 + 反胃 + 击退）、
 *       探（半径 = 施法范围，9 点 + 生物发光显形 10 秒）。</li>
 *   <li>中阶·杀乐：弦（半径 5 范围音弦各 30 点 + 击退，全部命中）、
 *       弓（视线贯穿直线，途中全部敌对各 60 点 + 击退）、
 *       爆（落点音爆 5 格 90 点 + 失明 5 秒 + 强击退）。</li>
 *   <li>高阶·断葬曲：跟随领域 30 秒，半径 = 施法范围，圈内敌对每秒 100 点音系伤害 + 失明 + 缓慢。</li>
 *   <li>超阶·曲终帝陨：跟随结界 60 秒（半径 14 格），圈内敌对每秒 500 点 + 失明 + 缓慢，
 *       每 5 秒一次「帝陨」冲击波额外 200 点并强击退；施法者音系技能伤害 +100%。</li>
 * </ul>
 *
 * <p>伤害结算口径与毒系一致：DOT / 领域伤害先按「施法者 + 目标」每 tick 聚合后一次结算，
 * 避免多层音伤被原版 hurtTime 无敌帧吞掉；结算时乘上施法者的音系加成（超阶结界 +100%）。</p>
 *
 * <p>领域圈走 {@link MagicCircleSync} 客户端渲染层（跟随玩家、本地倒计时），不生成贴地实体、不每 tick 发包。</p>
 */
public final class SoundMagic {

    private SoundMagic() {
    }

    // ---------------- 时长（tick） ----------------
    private static final int BLIND_SHORT = 3 * 20;
    private static final int NAUSEA_TICKS = 3 * 20;
    private static final int GLOW_TICKS = 10 * 20;
    private static final int BLIND_BURST = 5 * 20;
    private static final int DIRGE_TICKS = 30 * 20;
    private static final int FINALE_TICKS = 60 * 20;
    /** 领域 DOT 每秒结算一次 */
    private static final int DOT_TICKS = 20;
    /** 帝陨冲击波周期（5 秒） */
    private static final int DOOM_PULSE_TICKS = 5 * 20;

    // ---------------- 伤害数值（2026-11-16 调整：全系范围 + 穿透） ----------------
    private static final double KILL_DAMAGE = 3.0D;
    private static final double DISRUPT_DAMAGE = 6.0D;
    private static final double PROBE_DAMAGE = 9.0D;
    /** 弦：用户本次未指定数值，沿用原 30 点 */
    private static final double STRING_DAMAGE = 30.0D;
    private static final double ARROW_DAMAGE = 60.0D;
    private static final double BURST_DAMAGE = 90.0D;
    private static final double DIRGE_DPS = 100.0D;
    private static final double FINALE_DPS = 500.0D;
    private static final double DOOM_BLAST = 200.0D;

    /** 中阶落点范围（固定值，与施法范围无关） */
    private static final double DISRUPT_RADIUS = 4.0D;
    private static final double BURST_RADIUS = 5.0D;
    /** 弦的范围半径 */
    private static final double STRING_RADIUS = 5.0D;
    /** 杀：穿透音刃沿途的判定半径 */
    private static final double KILL_BEAM_RADIUS = 1.6D;

    // ---------------- 运行时状态（不存档） ----------------
    private enum Kind { DIRGE, FINALE }

    /** 高阶 / 超阶跟随领域 */
    private static final List<Aura> AURAS = new ArrayList<>();
    /** 每 tick 待结算：施法者 -> (目标 -> 本 tick 累计伤害) */
    private static final Map<ServerPlayer, Map<LivingEntity, Double>> BATCH = new HashMap<>();

    // ==================================================================
    // 初阶·音弦
    // ==================================================================

    /** 杀：一道穿透音刃沿视线穿出，沿途全部敌对 3 点 + 失明 3 秒（范围 + 穿透，无命中上限） */
    public static void kill(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 start = ctx.origin();
        Vec3 dir = player.getLookAngle().normalize();
        double range = ctx.range();
        // 沿视线穿透，命中沿途所有敌对（不设上限）
        for (LivingEntity entity : targetsAlongBeam(player, start, dir, range, KILL_BEAM_RADIUS)) {
            ctx.damage(entity, KILL_DAMAGE * casterDamageMultiplier(player));
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_SHORT, 0));
            ringParticles(level, entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D), 1.0D, 10);
        }
        MagicDamage.line(level, start, start.add(dir.scale(range)), soundParticle(), 0.3D);
        play(level, start.add(dir.scale(Math.min(range, 6.0D))), SoundEvents.BELL_RESONATE, 1.0F, 1.6F);
    }

    /** 扰：准星落点震荡，半径 4 格内敌对 4 点 + 反胃 3 秒 + 击退 */
    public static void disrupt(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        for (LivingEntity entity : hostilesInRange(level, ctx.player(), center, DISRUPT_RADIUS)) {
            ctx.damage(entity, DISRUPT_DAMAGE * casterDamageMultiplier(ctx.player()));
            entity.addEffect(new MobEffectInstance(MobEffects.NAUSEA, NAUSEA_TICKS, 0));
            knockbackAway(center, entity, 0.6D);
        }
        ringParticles(level, center, DISRUPT_RADIUS, 20);
        play(level, center, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.1F, 0.7F);
    }

    /** 探：声波脉冲，半径 = 施法范围，范围内敌对 9 点 + 全部生物发光显形 10 秒 */
    public static void probe(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.player().position();
        double radius = ctx.range();
        int count = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            if (!entity.isAlive() || entity.isSpectator() || entity == ctx.player()
                    || !within(center, entity.position(), radius)) {
                continue;
            }
            if (hostile(ctx.player(), entity)) {
                ctx.damage(entity, PROBE_DAMAGE * casterDamageMultiplier(ctx.player()));
            }
            entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0));
            count++;
        }
        // 三圈扩散的音波
        for (int wave = 1; wave <= 3; wave++) {
            double r = radius * wave / 3.0D;
            ringParticles(level, center, r, 24);
        }
        play(level, center, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.2F);
        NoticeApi.send(ctx.player(), Component.literal("音弦·探: 显形 " + count + " 个目标")
                .withStyle(ChatFormatting.GOLD));
    }

    // ==================================================================
    // 中阶·杀乐
    // ==================================================================

    /** 弦：落点半径 5 范围音弦，命中范围内全部敌对（穿透、无上限）各 30 点 + 击退 */
    public static void strings(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        List<LivingEntity> targets = hostilesInRange(level, ctx.player(), center, STRING_RADIUS);
        if (targets.isEmpty()) {
            for (int i = 0; i < 3; i++) {
                MagicDamage.line(level, ctx.origin(), center, soundParticle(), 0.3D);
            }
            play(level, center, SoundEvents.BELL_RESONATE, 0.8F, 1.4F);
            return;
        }
        for (LivingEntity target : targets) {
            Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
            MagicDamage.line(level, ctx.origin(), hit, soundParticle(), 0.3D);
            ctx.damage(target, STRING_DAMAGE * casterDamageMultiplier(ctx.player()));
            knockbackAway(ctx.origin(), target, 0.9D);
            ringParticles(level, hit, 1.0D, 10);
        }
        ringParticles(level, center, STRING_RADIUS, 20);
        play(level, center, SoundEvents.BELL_RESONATE, 1.0F, 1.3F);
    }

    /** 弓：一道贯穿音箭沿视线飞出，穿透路径上全部敌对（无上限）各 60 点 + 击退 */
    public static void bow(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 start = ctx.origin();
        Vec3 dir = player.getLookAngle().normalize();
        double range = ctx.range();
        for (LivingEntity entity : targetsAlongBeam(player, start, dir, range, 1.2D)) {
            ctx.damage(entity, ARROW_DAMAGE * casterDamageMultiplier(player));
            knockbackAway(start, entity, 1.2D);
            ringParticles(level, entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D), 1.1D, 12);
        }
        MagicDamage.line(level, start, start.add(dir.scale(range)), soundParticle(), 0.35D);
        play(level, start.add(dir.scale(Math.min(range, 6.0D))), SoundEvents.BREEZE_SHOOT, 1.0F, 1.3F);
    }

    /** 爆：落点音爆，半径 5 格 60 点 + 失明 5 秒 + 强击退 */
    public static void burst(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        for (LivingEntity entity : hostilesInRange(level, ctx.player(), center, BURST_RADIUS)) {
            ctx.damage(entity, BURST_DAMAGE * casterDamageMultiplier(ctx.player()));
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_BURST, 0));
            knockbackAway(center, entity, 1.6D);
        }
        ringParticles(level, center, BURST_RADIUS, 32);
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.6D, center.z,
                8, 1.0D, 0.8D, 1.0D, 0.1D);
        play(level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 1.4F);
    }

    // ==================================================================
    // 高阶 / 超阶
    // ==================================================================

    /** 断葬曲：跟随领域 30 秒，半径 = 施法范围，圈内敌对每秒 100 点 + 失明 + 缓慢 */
    public static void dirge(SkillContext ctx) {
        spawnAura(ctx, Kind.DIRGE, DIRGE_TICKS, MagicCirclePayload.SOUND_DIRGE);
    }

    /** 曲终帝陨：跟随结界 60 秒，半径 14 格，圈内敌对每秒 500 点 + 失明 + 缓慢，每 5 秒帝陨冲击波 */
    public static void finale(SkillContext ctx) {
        spawnAura(ctx, Kind.FINALE, FINALE_TICKS, MagicCirclePayload.SOUND_FINALE);
    }

    private static void spawnAura(SkillContext ctx, Kind kind, int ticks, int circleVariant) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        float radius = kind == Kind.FINALE
                ? (float) Math.min(ctx.range(), 14.0D)
                : (float) ctx.range();
        beginTimed(ctx, ticks);
        @Nullable FxBillboard display = DisplayFx.spawn(level,
                player.position().add(0.0D, 2.7D, 0.0D),
                kind == Kind.DIRGE ? MagicItems.SOUND_LYRE.get() : MagicItems.SOUND_NOTE.get(),
                1.3F, 1.3F, 1.3F, Display.BillboardConstraints.CENTER);
        AURAS.add(new Aura(kind, player, level, radius, display, circleVariant, now + ticks,
                now + DOT_TICKS, now + DOOM_PULSE_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        MagicCircleSync.start(player, circleVariant, ticks, radius);
        Vec3 at = player.position().add(0.0D, 0.15D, 0.0D);
        for (int wave = 1; wave <= 3; wave++) {
            ringParticles(level, at, radius * wave / 3.0D, 28);
        }
        play(level, at, SoundEvents.BELL_RESONATE, 1.2F, 0.7F);
        play(level, at, SoundEvents.TOTEM_USE, 1.0F, 1.2F);
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick 调用）
    // ==================================================================

    public static void tickWorld() {
        BATCH.clear();
        long now = -1L;
        for (int i = AURAS.size() - 1; i >= 0; i--) {
            Aura aura = AURAS.get(i);
            ServerLevel level = aura.level;
            ServerPlayer owner = aura.owner;
            now = level.getGameTime();
            if (!owner.isAlive() || now >= aura.endTick) {
                discardAura(aura);
                finishTimed(owner, aura.skillId, aura.cooldownSeconds, now);
                AURAS.remove(i);
                continue;
            }
            if (aura.display != null && aura.display.isAlive()) {
                Vec3 at = owner.position().add(0.0D, 2.7D, 0.0D);
                aura.display.setPos(at.x, at.y + Math.sin(now * 0.05D) * 0.12D, at.z);
            }
            List<LivingEntity> enemies = hostilesInRange(level, owner, owner.position(), aura.radius);
            double dps = aura.kind == Kind.FINALE ? FINALE_DPS : DIRGE_DPS;
            if (now >= aura.nextPulseTick) {
                aura.nextPulseTick = now + DOT_TICKS;
                for (LivingEntity entity : enemies) {
                    addDamage(owner, entity, dps);
                    entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0));
                    entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
                }
                if (now % 40L == 0L) {
                    ringParticles(level, owner.position(), aura.radius, 20);
                }
            }
            if (aura.kind == Kind.FINALE && now >= aura.nextDoomTick) {
                aura.nextDoomTick = now + DOOM_PULSE_TICKS;
                Vec3 at = owner.position();
                for (LivingEntity entity : enemies) {
                    addDamage(owner, entity, DOOM_BLAST);
                    knockbackAway(at, entity, 2.0D);
                }
                for (int wave = 1; wave <= 3; wave++) {
                    ringParticles(level, at, aura.radius * wave / 3.0D, 32);
                }
                level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.8D, at.z,
                        10, aura.radius * 0.3D, 0.6D, aura.radius * 0.3D, 0.05D);
                play(level, at, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.3F, 0.6F);
                play(level, at, SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 1.2F);
            }
        }
        flush();
    }

    private static void addDamage(ServerPlayer owner, LivingEntity target, double base) {
        if (owner == null || target == null || target == owner || !target.isAlive() || target.isRemoved()) {
            return;
        }
        BATCH.computeIfAbsent(owner, key -> new HashMap<>())
                .merge(target, base * casterDamageMultiplier(owner), Double::sum);
    }

    private static void flush() {
        for (Map.Entry<ServerPlayer, Map<LivingEntity, Double>> caster : BATCH.entrySet()) {
            ServerPlayer owner = caster.getKey();
            for (Map.Entry<LivingEntity, Double> hit : caster.getValue().entrySet()) {
                LivingEntity target = hit.getKey();
                if (target == owner || !target.isAlive() || target.isRemoved()) {
                    continue;
                }
                MagicDamage.magicDamage(owner, target, hit.getValue(), MagicElement.SOUND);
            }
        }
    }

    /**
     * 施法者音系伤害加成：曲终帝陨结界期间 +100%（与毒系万噬之风同口径）。
     * 只在 SoundMagic 自身的伤害调用里乘算，因此只增幅音系技能，不影响其它系列。
     */
    public static float casterDamageMultiplier(LivingEntity caster) {
        for (Aura aura : AURAS) {
            if (aura.kind == Kind.FINALE && aura.owner == caster) {
                return 2.0F;
            }
        }
        return 1.0F;
    }

    // ==================================================================
    // 清理
    // ==================================================================

    public static void removePlayer(ServerPlayer player) {
        for (int i = AURAS.size() - 1; i >= 0; i--) {
            if (AURAS.get(i).owner == player) {
                discardAura(AURAS.remove(i));
            }
        }
        MagicCircleSync.stop(player, MagicCirclePayload.SOUND_DIRGE);
        MagicCircleSync.stop(player, MagicCirclePayload.SOUND_FINALE);
    }

    public static void clearAll() {
        for (Aura aura : AURAS) {
            if (aura.display != null) {
                aura.display.discard();
            }
        }
        AURAS.clear();
        BATCH.clear();
    }

    private static void discardAura(Aura aura) {
        if (aura.display != null) {
            aura.display.discard();
        }
        MagicCircleSync.stop(aura.owner, aura.circleVariant);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 沿视线穿透取全部敌对（穿透性核心）：按 0.6 格步进采样，每个敌对只记一次，
     * 不设命中上限。用于「杀」「弓」这类直线穿透技能。
     */
    private static List<LivingEntity> targetsAlongBeam(ServerPlayer owner, Vec3 start,
                                                        Vec3 dir, double range, double radius) {
        ServerLevel level = (ServerLevel) owner.level();
        List<LivingEntity> result = new ArrayList<>();
        for (double d = 0.6D; d <= range; d += 0.6D) {
            Vec3 pos = start.add(dir.scale(d));
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(pos, pos).inflate(radius + 0.4D))) {
                if (!entity.isAlive() || entity == owner || result.contains(entity)
                        || !hostile(owner, entity) || !within(pos, entity.position(), radius)) {
                    continue;
                }
                result.add(entity);
            }
        }
        return result;
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
        return distanceSq(center, pos) <= radius * radius;
    }

    private static double distanceSq(Vec3 center, Vec3 pos) {
        double dx = pos.x - center.x;
        double dz = pos.z - center.z;
        return dx * dx + dz * dz;
    }

    /** 水平击退：从 origin 指向目标的方向，附加一个上抛分量 */
    private static void knockbackAway(Vec3 origin, LivingEntity target, double strength) {
        double dx = target.getX() - origin.x;
        double dz = target.getZ() - origin.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            dx = 1.0D;
            dz = 0.0D;
            len = 1.0D;
        }
        Vec3 push = new Vec3(dx / len * strength, 0.24D, dz / len * strength);
        target.setDeltaMovement(target.getDeltaMovement().add(push));
    }

    private static ColorParticleOption soundParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.SOUND.color());
    }

    private static void ringParticles(ServerLevel level, Vec3 center, double radius, int count) {
        double phase = level.getGameTime() * 0.06D;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count + phase;
            level.sendParticles(soundParticle(),
                    center.x + Math.cos(angle) * radius, center.y + 0.2D,
                    center.z + Math.sin(angle) * radius,
                    2, 0.04D, 0.16D, 0.04D, 0.0D);
        }
    }

    private static void play(ServerLevel level, Vec3 pos, SoundEvent sound, float volume, float pitch) {
        MagicDamage.playSound(level, pos, sound, volume, pitch);
    }

    /** 持续技能统一开局 */
    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

    /** 持续技能统一收尾 */
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

    private static final class Aura {
        final Kind kind;
        final ServerPlayer owner;
        final ServerLevel level;
        final float radius;
        final @Nullable FxBillboard display;
        final int circleVariant;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        long nextPulseTick;
        long nextDoomTick;

        Aura(Kind kind, ServerPlayer owner, ServerLevel level, float radius,
             @Nullable FxBillboard display, int circleVariant, long endTick,
             long nextPulseTick, long nextDoomTick, String skillId, int cooldownSeconds) {
            this.kind = kind;
            this.owner = owner;
            this.level = level;
            this.radius = radius;
            this.display = display;
            this.circleVariant = circleVariant;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.nextDoomTick = nextDoomTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
}
