package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.network.MagicCirclePayload;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 治愈系 8 个技能（2026-11-14 设计实现）。
 *
 * <p>四阶沿用需求文档的组名：不药而愈 / 治愈精灵 / 宝瓶星芒座 / 复苏，
 * 并按其它已实现系列的规格补齐为 初阶 3 + 中阶 3 + 高阶 1 + 超阶 1。</p>
 *
 * <ul>
 *   <li>初阶·不药而愈：愈合（单体治疗 8 点，亡灵改为 3 点伤害）、净化（驱散 + 10 秒负面免疫）、
 *       生机（对准友方释放，30 秒持续再生 + 双防 +10%）。</li>
 *   <li>中阶·治愈精灵：幼芽 / 环舞 / 群愈，分别召出 1 / 2 / 3 只跟随玩家的治愈精灵，
 *       每秒按范围治疗友方 10 / 15 / 20 点，群愈额外提供双防 +20%。</li>
 *   <li>高阶·宝瓶星芒座：跟随玩家的星芒领域 20 秒，圈内友方每秒回 100 点并驱散，
 *       亡灵每秒受 100 点治愈系伤害，友方双防 +20%。</li>
 *   <li>超阶·复苏：跟随玩家的复苏结界 60 秒，圈内友方每秒回 500 点、亡灵每秒受
 *       500 点治愈系伤害、驱散全部负面、双防 +50%，
 *       且每个友方拥有一次「致死伤害取消并恢复到 50% 生命」。</li>
 * </ul>
 *
 * <p>所有领域圈都走 {@link MagicCircleSync} 的客户端渲染层（只在开启/结束各发一次状态包，
 * 跟随玩家、本地倒计时），不在服务端生成贴地实体、不每 tick 发包。超阶圆环由
 * {@code MagicCaster} 自动开启，{@link #beginTimed} 的技能锁定会把它延长到效果结束。</p>
 *
 * <p>防御增减与其它系同口径：在 {@code MagicEvents} 的伤害事件里乘算，
 * 「防御 +N%」= 受伤 ÷(1+N%)。治疗量在复苏结界内 ×1.5。</p>
 */
public final class HealMagic {

    private HealMagic() {
    }

    // ---------------- 时长与数值（tick） ----------------
    private static final int CLEANSE_TICKS = 10 * 20;
    private static final int VIGOR_TICKS = 30 * 20;
    private static final int SPRITE_TICKS = 20 * 20;
    private static final int RING_TICKS = 25 * 20;
    private static final int FLOCK_TICKS = 30 * 20;
    private static final int STAR_TICKS = 20 * 20;
    private static final int REBIRTH_TICKS = 60 * 20;
    /** 生机 / 净化等持续技能的脉冲间隔（2 秒） */
    private static final int PULSE_TICKS = 40;
    /** 治愈精灵：每秒结算一次治疗 */
    private static final int SPRITE_PULSE_TICKS = 20;
    /** 领域每秒结算一次 */
    private static final int AURA_PULSE_TICKS = 20;

    private static final float MEND_HEAL = 8.0F;
    /** 愈合指向亡灵时改为造成 3 点治愈系伤害（治疗量仍为 8 点） */
    private static final float MEND_UNDEAD_DAMAGE = 3.0F;
    private static final float CLEANSE_HEAL = 4.0F;
    private static final float VIGOR_HEAL = 3.0F;
    private static final float SPRITE_HEAL = 10.0F;
    private static final float RING_HEAL = 15.0F;
    private static final float FLOCK_HEAL = 20.0F;
    private static final float STAR_HEAL = 100.0F;
    private static final double STAR_UNDEAD_DPS = 100.0D;
    private static final float REBIRTH_HEAL = 500.0F;
    private static final double REBIRTH_UNDEAD_DPS = 500.0D;

    /** 防御增益：生机 +10% / 群愈 +20% / 星芒座 +20% / 复苏 +50% */
    private static final double VIGOR_DEF = 1.0D / 1.10D;
    private static final double FLOCK_DEF = 1.0D / 1.20D;
    private static final double STAR_DEF = 1.0D / 1.20D;
    private static final double REBIRTH_DEF = 1.0D / 1.50D;
    /** 复苏结界内所有治疗量 ×1.5 */
    private static final float REBIRTH_HEAL_MUL = 1.5F;

    // ---------------- 运行时状态（不存档） ----------------
    private enum AuraKind { STAR, REBIRTH }

    /** 净化：10 秒内持续压掉新挂上的负面效果 */
    private static final List<Cleanse> CLEANSES = new ArrayList<>();
    /** 生机：30 秒持续再生 */
    private static final List<Vigor> VIGORS = new ArrayList<>();
    /** 治愈精灵群 */
    private static final List<Flock> FLOCKS = new ArrayList<>();
    /** 星芒座 / 复苏结界 */
    private static final List<Aura> AURAS = new ArrayList<>();

    // ==================================================================
    // 初阶·不药而愈
    // ==================================================================

    /** 愈合：对准友方（或按住 Ctrl 强制自己）立即回 8 点 + 4 秒生命恢复 II；亡灵改为 3 点治愈系伤害 */
    public static void mend(SkillContext ctx) {
        ServerLevel level = ctx.level();
        LivingEntity target = resolveMendTarget(ctx);
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, healParticle(), 0.25D);
        level.sendParticles(ParticleTypes.HEART, hit.x, hit.y, hit.z, 4, 0.3D, 0.3D, 0.3D, 0.04D);
        if (isUndead(target)) {
            // 治愈之光对亡灵是灼烧：走统一魔法伤害管线，带魔力/暴击/伤害数字
            ctx.damage(target, MEND_UNDEAD_DAMAGE);
            MagicDamage.playSound(level, hit, SoundEvents.AMETHYST_BLOCK_CHIME, 0.9F, 0.6F);
        } else {
            healEntity(target, MEND_HEAL);
            target.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 1));
            MagicDamage.playSound(level, hit, SoundEvents.BEACON_ACTIVATE, 0.7F, 1.5F);
        }
        burst(level, hit, 12);
    }

    /** 净化：清除目标全部负面效果、回 4 点，并在 10 秒内持续压掉新挂上的负面 */
    public static void cleanse(SkillContext ctx) {
        ServerLevel level = ctx.level();
        LivingEntity target = resolveAlly(ctx);
        cleanseEntity(target);
        healEntity(target, CLEANSE_HEAL);
        long now = level.getGameTime();
        CLEANSES.removeIf(state -> state.entity == target);
        CLEANSES.add(new Cleanse(target, now + CLEANSE_TICKS, now + 10L));
        Vec3 pos = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x, pos.y, pos.z,
                18, 0.6D, 0.7D, 0.6D, 0.05D);
        burst(level, pos, 16);
        MagicDamage.playSound(level, pos, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.4F);
    }

    /**
     * 生机：对准友方释放（按住 Ctrl 或没指到友方时作用于自己）。
     * 30 秒内每 2 秒回 3 点、双防 +10%，立即获得 30 秒伤害吸收 I。
     */
    public static void vigor(SkillContext ctx) {
        ServerLevel level = ctx.level();
        LivingEntity target = resolveAlly(ctx);
        long now = level.getGameTime();
        VIGORS.removeIf(state -> state.entity == target);
        VIGORS.add(new Vigor(target, now + VIGOR_TICKS, now + PULSE_TICKS));
        target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, VIGOR_TICKS, 0));
        Vec3 pos = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        // 对准友方时画一条治愈光线，明确指示这次生机落在谁身上（作用于自己时不需要）
        if (target != ctx.player()) {
            MagicDamage.line(level, ctx.origin(), pos, healParticle(), 0.25D);
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x, pos.y, pos.z,
                24, 0.5D, 1.0D, 0.5D, 0.04D);
        MagicDamage.playSound(level, pos, SoundEvents.BEACON_ACTIVATE, 0.8F, 1.2F);
    }

    // ==================================================================
    // 中阶·治愈精灵
    // ==================================================================

    /** 幼芽：1 只精灵跟随，20 秒内每秒治疗半径 6 格内血量最低的 1 名友方 10 点 */
    public static void sprite(SkillContext ctx) {
        summonSprites(ctx, 1, SPRITE_TICKS, 6.0D, SPRITE_HEAL, FlockKind.SINGLE);
    }

    /** 环舞：2 只精灵环绕，25 秒内每秒治疗半径 8 格内全部友方 15 点并驱散负面 */
    public static void spriteRing(SkillContext ctx) {
        summonSprites(ctx, 2, RING_TICKS, 8.0D, RING_HEAL, FlockKind.RING);
    }

    /** 群愈：3 只精灵，30 秒内每秒治疗半径 12 格内全部友方 20 点 + 再生 I，友方双防 +20% */
    public static void spriteFlock(SkillContext ctx) {
        summonSprites(ctx, 3, FLOCK_TICKS, 12.0D, FLOCK_HEAL, FlockKind.FLOCK);
    }

    private enum FlockKind { SINGLE, RING, FLOCK }

    private static void summonSprites(SkillContext ctx, int count, int ticks, double radius,
                                      float heal, FlockKind kind) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        List<FxBillboard> displays = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Vec3 pos = spritePos(player, now, i, count);
            FxBillboard display = DisplayFx.spawn(level, pos, MagicItems.HEAL_SPRITE.get(),
                    0.65F, 0.65F, 0.65F, Display.BillboardConstraints.CENTER);
            if (display != null) {
                displays.add(display);
            }
        }
        if (displays.isEmpty()) {
            failCast(ctx, "治愈精灵生成失败");
            return;
        }
        beginTimed(ctx, ticks);
        FLOCKS.add(new Flock(player, level, displays, kind, radius, heal, now + ticks,
                now + SPRITE_PULSE_TICKS, ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        Vec3 at = player.position().add(0.0D, 1.0D, 0.0D);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z,
                20, 0.7D, 0.8D, 0.7D, 0.04D);
        MagicDamage.playSound(level, at, SoundEvents.BEACON_ACTIVATE, 0.9F, 1.6F);
    }

    // ==================================================================
    // 高阶 / 超阶
    // ==================================================================

    /** 宝瓶星芒座：跟随玩家的星芒领域 20 秒，圈内友方每秒回 100 点并驱散，亡灵每秒受 100 点治愈系伤害 */
    public static void starConstellation(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        float radius = (float) ctx.range();
        beginTimed(ctx, STAR_TICKS);
        FxBillboard bottle = DisplayFx.spawn(level,
                player.position().add(0.0D, 2.6D, 0.0D), MagicItems.HEAL_BOTTLE.get(),
                1.2F, 1.8F, 1.2F, Display.BillboardConstraints.CENTER);
        AURAS.add(new Aura(player, level, AuraKind.STAR, radius, STAR_HEAL, now + STAR_TICKS,
                now + AURA_PULSE_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), bottle));
        MagicCircleSync.start(player, MagicCirclePayload.HEAL_STAR, STAR_TICKS, radius);
        Vec3 at = player.position().add(0.0D, 0.15D, 0.0D);
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2.0D / 12.0D;
            level.sendParticles(healParticle(), at.x + Math.cos(angle) * radius,
                    at.y, at.z + Math.sin(angle) * radius, 6, 0.05D, 0.4D, 0.05D, 0.0D);
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 1.2D, at.z,
                30, 1.2D, 1.4D, 1.2D, 0.05D);
        MagicDamage.playSound(level, at, SoundEvents.BEACON_ACTIVATE, 1.2F, 1.3F);
        MagicDamage.playSound(level, at, SoundEvents.PLAYER_LEVELUP, 0.8F, 1.4F);
    }

    /**
     * 复苏：跟随玩家的结界 60 秒。圈内友方每秒回 500 点、亡灵每秒受 500 点治愈系伤害、
     * 驱散全部负面、双防 +50%，且每个友方拥有一次
     * 「致死伤害取消并恢复到 50% 最大生命」（{@link #onIncomingDamage}）。
     */
    public static void rebirth(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        // 结界是「自身周围」的固有尺寸，不跟着超阶 30 格施法范围铺满整张地图；判定与视觉同半径
        float radius = (float) Math.min(ctx.range(), 12.0D);
        beginTimed(ctx, REBIRTH_TICKS);
        FxBillboard sigil = DisplayFx.spawn(level,
                player.position().add(0.0D, 2.8D, 0.0D), MagicItems.HEAL_SIGIL.get(),
                1.4F, 1.4F, 1.4F, Display.BillboardConstraints.CENTER);
        AURAS.add(new Aura(player, level, AuraKind.REBIRTH, radius, REBIRTH_HEAL, now + REBIRTH_TICKS,
                now + AURA_PULSE_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), sigil));
        MagicCircleSync.start(player, MagicCirclePayload.HEAL_REBIRTH, REBIRTH_TICKS, radius);
        Vec3 at = player.position().add(0.0D, 0.15D, 0.0D);
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2.0D / 24.0D;
            level.sendParticles(ParticleTypes.END_ROD, at.x + Math.cos(angle) * radius,
                    at.y + 0.6D, at.z + Math.sin(angle) * radius, 4, 0.04D, 0.5D, 0.04D, 0.0D);
        }
        level.sendParticles(ParticleTypes.HEART, at.x, at.y + 1.0D, at.z,
                24, 1.0D, 1.0D, 1.0D, 0.05D);
        MagicDamage.playSound(level, at, SoundEvents.TOTEM_USE, 1.2F, 1.0F);
        MagicDamage.playSound(level, at, SoundEvents.PLAYER_LEVELUP, 1.0F, 1.2F);
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick 调用）
    // ==================================================================

    public static void tickWorld() {
        tickCleanses();
        tickVigors();
        tickFlocks();
        tickAuras();
    }

    private static void tickCleanses() {
        for (int i = CLEANSES.size() - 1; i >= 0; i--) {
            Cleanse state = CLEANSES.get(i);
            if (!state.entity.isAlive() || state.entity.isRemoved()) {
                CLEANSES.remove(i);
                continue;
            }
            long now = state.entity.level().getGameTime();
            if (now >= state.endTick) {
                CLEANSES.remove(i);
                continue;
            }
            if (now >= state.nextTick) {
                state.nextTick = now + 10L;
                cleanseEntity(state.entity);
            }
        }
    }

    private static void tickVigors() {
        for (int i = VIGORS.size() - 1; i >= 0; i--) {
            Vigor state = VIGORS.get(i);
            if (!state.entity.isAlive() || state.entity.isRemoved()) {
                VIGORS.remove(i);
                continue;
            }
            long now = state.entity.level().getGameTime();
            if (now >= state.endTick) {
                VIGORS.remove(i);
                continue;
            }
            if (now >= state.nextTick) {
                state.nextTick = now + PULSE_TICKS;
                healEntity(state.entity, VIGOR_HEAL);
            }
        }
    }

    private static void tickFlocks() {
        for (int i = FLOCKS.size() - 1; i >= 0; i--) {
            Flock flock = FLOCKS.get(i);
            ServerLevel level = flock.level;
            ServerPlayer owner = flock.owner;
            long now = level.getGameTime();
            if (!owner.isAlive() || now >= flock.endTick) {
                discardFlock(flock);
                finishTimed(owner, flock.skillId, flock.cooldownSeconds, now);
                FLOCKS.remove(i);
                continue;
            }
            updateSprites(flock, now);
            if (now >= flock.nextPulseTick) {
                flock.nextPulseTick = now + SPRITE_PULSE_TICKS;
                pulseFlock(flock, now);
            }
        }
    }

    private static void tickAuras() {
        for (int i = AURAS.size() - 1; i >= 0; i--) {
            Aura aura = AURAS.get(i);
            ServerLevel level = aura.level;
            ServerPlayer owner = aura.owner;
            long now = level.getGameTime();
            if (!owner.isAlive() || now >= aura.endTick) {
                discardAura(aura);
                finishTimed(owner, aura.skillId, aura.cooldownSeconds, now);
                AURAS.remove(i);
                continue;
            }
            if (aura.display != null && aura.display.isAlive()) {
                Vec3 at = owner.position().add(0.0D, aura.kind == AuraKind.STAR ? 2.6D : 2.8D, 0.0D);
                aura.display.setPos(at.x, at.y + Math.sin(now * 0.05D) * 0.12D, at.z);
            }
            if (now >= aura.nextPulseTick) {
                aura.nextPulseTick = now + AURA_PULSE_TICKS;
                pulseAura(aura, now);
            }
        }
    }

    /** 精灵绕玩家缓慢旋转，服务端只改实体位置；治疗范围本身不画圆圈 */
    private static void updateSprites(Flock flock, long now) {
        ServerPlayer owner = flock.owner;
        int count = flock.displays.size();
        for (int i = 0; i < count; i++) {
            Vec3 pos = spritePos(owner, now, i, count);
            FxBillboard display = flock.displays.get(i);
            if (display.isAlive()) {
                display.setPos(pos.x, pos.y, pos.z);
            }
        }
    }

    private static Vec3 spritePos(ServerPlayer owner, long now, int index, int count) {
        double phase = now * 0.045D + index * Math.PI * 2.0D / Math.max(1, count);
        double radius = count == 1 ? 1.1D : 1.6D;
        return owner.position().add(Math.cos(phase) * radius,
                1.35D + Math.sin(now * 0.07D + index) * 0.18D, Math.sin(phase) * radius);
    }

    private static void pulseFlock(Flock flock, long now) {
        List<LivingEntity> allies = alliesInRange(flock.level, flock.owner, flock.owner.position(), flock.radius);
        if (allies.isEmpty()) {
            return;
        }
        if (flock.kind == FlockKind.SINGLE) {
            LivingEntity lowest = null;
            float missing = 0.0F;
            for (LivingEntity ally : allies) {
                float gap = ally.getMaxHealth() - ally.getHealth();
                if (gap > missing) {
                    missing = gap;
                    lowest = ally;
                }
            }
            if (lowest != null && missing > 0.0F) {
                healEntity(lowest, flock.heal);
            }
        } else {
            for (LivingEntity ally : allies) {
                healEntity(ally, flock.heal);
                if (flock.kind == FlockKind.RING) {
                    cleanseEntity(ally);
                } else {
                    ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0));
                }
            }
        }
        if (now % 80L == 0L) {
            MagicDamage.playSound(flock.level, flock.owner.position(), SoundEvents.BEACON_POWER_SELECT, 0.35F, 1.7F);
        }
    }

    private static void pulseAura(Aura aura, long now) {
        ServerLevel level = aura.level;
        ServerPlayer owner = aura.owner;
        Vec3 center = owner.position();
        double radius = aura.radius;
        boolean rebirth = aura.kind == AuraKind.REBIRTH;
        for (LivingEntity ally : alliesInRange(level, owner, center, radius)) {
            // 复苏自身脉冲按技能数值精确结算，不再被自己的「结界内治疗 +50%」二次放大；
            // 其它来源的治疗（愈合 / 精灵 / 生机 / 星芒座）落在结界内仍照常享受 ×1.5
            healEntity(ally, aura.heal, !rebirth);
            cleanseEntity(ally);
            ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, rebirth ? 1 : 0));
        }
        // 星芒净化 / 复苏圣光：领域内的亡灵每秒受到治愈系伤害（星芒座 100、复苏 500）
        double undeadDamage = rebirth ? REBIRTH_UNDEAD_DPS : STAR_UNDEAD_DPS;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            if (!entity.isAlive() || entity == owner || !isUndead(entity)
                    || flatDistance(center, entity.position()) > radius) {
                continue;
            }
            MagicDamage.magicDamage(owner, entity, undeadDamage, MagicElement.HEAL);
            level.sendParticles(healParticle(), entity.getX(),
                    entity.getY() + entity.getBbHeight() * 0.6D, entity.getZ(),
                    6, 0.2D, 0.3D, 0.2D, 0.03D);
        }
        if (now % 80L == 0L) {
            level.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 0.4D, center.z,
                    12, radius * 0.4D, 0.6D, radius * 0.4D, 0.02D);
        }
    }

    // ==================================================================
    // 伤害事件：防御增益 / 致死复苏
    // ==================================================================

    /** 与其它系列乘算叠加：生机 +10%、群愈 +20%、星芒座 +20%、复苏 +50% */
    public static float incomingMultiplier(LivingEntity entity, boolean magic) {
        double multiplier = 1.0D;
        for (Vigor state : VIGORS) {
            if (state.entity == entity) {
                multiplier *= VIGOR_DEF;
            }
        }
        for (Flock flock : FLOCKS) {
            if (flock.kind == FlockKind.FLOCK && friendly(flock.owner, entity)
                    && within(flock.owner.position(), entity.position(), flock.radius)) {
                multiplier *= FLOCK_DEF;
            }
        }
        for (Aura aura : AURAS) {
            if (!friendly(aura.owner, entity) || !within(aura.owner.position(), entity.position(), aura.radius)) {
                continue;
            }
            multiplier *= aura.kind == AuraKind.STAR ? STAR_DEF : REBIRTH_DEF;
        }
        return (float) multiplier;
    }

    /**
     * 复苏结界的「不死」：结界内友方第一次受到致死伤害时取消该次伤害，
     * 并把血量直接恢复到 50% 最大生命；每个结界对每个实体只生效一次。
     * 由 {@code MagicEvents.onIncomingDamage} 在其它减伤/免疫结算之后调用。
     */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity == null || !entity.isAlive()) {
            return;
        }
        if (entity.getHealth() - event.getAmount() > 0.0F) {
            return;
        }
        for (Aura aura : AURAS) {
            if (aura.kind != AuraKind.REBIRTH || !friendly(aura.owner, entity)
                    || !within(aura.owner.position(), entity.position(), aura.radius)
                    || aura.used.contains(entity.getUUID())) {
                continue;
            }
            aura.used.add(entity.getUUID());
            event.setCanceled(true);
            entity.setHealth(Math.max(1.0F, entity.getMaxHealth() * 0.5F));
            if (entity.level() instanceof ServerLevel level) {
                Vec3 at = entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D);
                level.sendParticles(ParticleTypes.HEART, at.x, at.y, at.z,
                        24, 0.6D, 0.7D, 0.6D, 0.06D);
                level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z,
                        24, 0.5D, 1.2D, 0.5D, 0.04D);
                MagicDamage.playSound(level, at, SoundEvents.TOTEM_USE, 1.4F, 1.1F);
            }
            return;
        }
    }

    // ==================================================================
    // 清理
    // ==================================================================

    /** 玩家下线 / 死亡：清掉该玩家的全部持续状态与实体 */
    public static void removePlayer(ServerPlayer player) {
        CLEANSES.removeIf(state -> state.entity == player);
        VIGORS.removeIf(state -> state.entity == player);
        for (int i = FLOCKS.size() - 1; i >= 0; i--) {
            if (FLOCKS.get(i).owner == player) {
                discardFlock(FLOCKS.remove(i));
            }
        }
        for (int i = AURAS.size() - 1; i >= 0; i--) {
            if (AURAS.get(i).owner == player) {
                discardAura(AURAS.remove(i));
            }
        }
        MagicCircleSync.stop(player, MagicCirclePayload.HEAL_STAR);
        MagicCircleSync.stop(player, MagicCirclePayload.HEAL_REBIRTH);
    }

    /** 服务器关闭：全部收干净（关服时不发包，客户端退出世界会自己清缓存） */
    public static void clearAll() {
        CLEANSES.clear();
        VIGORS.clear();
        for (Flock flock : FLOCKS) {
            flock.displays.forEach(FxBillboard::discard);
        }
        FLOCKS.clear();
        for (Aura aura : AURAS) {
            if (aura.display != null) {
                aura.display.discard();
            }
        }
        AURAS.clear();
    }

    private static void discardFlock(Flock flock) {
        flock.displays.forEach(display -> display.discard());
        flock.displays.clear();
    }

    private static void discardAura(Aura aura) {
        if (aura.display != null) {
            aura.display.discard();
        }
        aura.used.clear();
        MagicCircleSync.stop(aura.owner, aura.kind == AuraKind.STAR
                ? MagicCirclePayload.HEAL_STAR : MagicCirclePayload.HEAL_REBIRTH);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 治疗量在复苏结界内 ×1.5，并给出心形粒子反馈 */
    private static void healEntity(LivingEntity entity, float base) {
        healEntity(entity, base, true);
    }

    /**
     * @param rebirthBonus 是否允许叠加复苏结界的「治疗 +50%」；
     *                     复苏结界自身的脉冲传 false，保证技能标称的每秒治疗量精确落地
     */
    private static void healEntity(LivingEntity entity, float base, boolean rebirthBonus) {
        if (!entity.isAlive()) {
            return;
        }
        float amount = base * (rebirthBonus && hasRebirth(entity) ? REBIRTH_HEAL_MUL : 1.0F);
        entity.heal(amount);
        if (entity.level() instanceof ServerLevel level && entity.getHealth() < entity.getMaxHealth()) {
            level.sendParticles(ParticleTypes.HEART, entity.getX(),
                    entity.getY() + entity.getBbHeight() * 0.8D, entity.getZ(),
                    2, 0.25D, 0.2D, 0.25D, 0.02D);
        }
    }

    private static boolean hasRebirth(LivingEntity entity) {
        for (Aura aura : AURAS) {
            if (aura.kind == AuraKind.REBIRTH && friendly(aura.owner, entity)
                    && within(aura.owner.position(), entity.position(), aura.radius)) {
                return true;
            }
        }
        return false;
    }

    /** 驱散全部负面状态（与光系「净化」同口径） */
    private static void cleanseEntity(LivingEntity entity) {
        for (MobEffectInstance effect : new ArrayList<>(entity.getActiveEffects())) {
            if (!effect.getEffect().value().isBeneficial()) {
                entity.removeEffect(effect.getEffect());
            }
        }
    }

    /** 目标选择：按住 Ctrl 或没指到友方时作用于自己，绝不误给敌人加血 */
    private static LivingEntity resolveAlly(SkillContext ctx) {
        if (ctx.ctrlDown()) {
            return ctx.player();
        }
        LivingEntity target = ctx.targetEntity();
        // 用 friendly 判定：其他玩家始终可增益，不受 PVP 开关影响
        if (target == null || !friendly(ctx.player(), target)) {
            return ctx.player();
        }
        return target;
    }

    /**
     * 愈合专用目标选择：亡灵属于敌对，但仍要能成为「治愈之光灼烧」的目标。
     * 除亡灵外一切敌对生物仍走 {@link #resolveAlly} 过滤，绝不会误加血。
     */
    private static LivingEntity resolveMendTarget(SkillContext ctx) {
        if (ctx.ctrlDown()) {
            return ctx.player();
        }
        LivingEntity target = ctx.targetEntity();
        if (target != null && target.isAlive() && !target.isSpectator() && isUndead(target)) {
            return target;
        }
        return resolveAlly(ctx);
    }

    private static List<LivingEntity> alliesInRange(ServerLevel level, ServerPlayer owner,
                                                     Vec3 center, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            // 用 friendly 判定：PVP 开启时其他玩家仍算友方，照样能吃领域治疗/增益
            if (!friendly(owner, entity) || !within(center, entity.position(), radius)) {
                continue;
            }
            result.add(entity);
        }
        return result;
    }

    private static boolean friendly(ServerPlayer owner, LivingEntity entity) {
        // 其他玩家永远是合法增益目标（不受 PVP 开关影响），这才是"增益玩家"该有的行为
        return CombatTracker.isAllyTarget(owner, entity);
    }

    private static boolean hostile(ServerPlayer owner, LivingEntity entity) {
        return CombatTracker.isEnemyTarget(owner, entity);
    }

    private static boolean isUndead(LivingEntity entity) {
        return entity.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD);
    }

    private static boolean within(Vec3 center, Vec3 pos, double radius) {
        double dx = pos.x - center.x;
        double dz = pos.z - center.z;
        return dx * dx + dz * dz <= radius * radius;
    }

    private static double flatDistance(Vec3 from, Vec3 to) {
        return Math.sqrt((to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z));
    }

    private static ColorParticleOption healParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.HEAL.color());
    }

    private static void burst(ServerLevel level, Vec3 pos, int count) {
        level.sendParticles(healParticle(), pos.x, pos.y, pos.z,
                count, 0.35D, 0.5D, 0.35D, 0.04D);
    }

    /** 持续技能统一开局：清掉预写冷却并锁住该技能（超阶圆环由锁定自动延长） */
    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

    /** 持续技能统一收尾：写完整冷却 + 收超阶圆环 */
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

    private static final class Cleanse {
        final LivingEntity entity;
        final long endTick;
        long nextTick;

        Cleanse(LivingEntity entity, long endTick, long nextTick) {
            this.entity = entity;
            this.endTick = endTick;
            this.nextTick = nextTick;
        }
    }

    private static final class Vigor {
        final LivingEntity entity;
        final long endTick;
        long nextTick;

        Vigor(LivingEntity entity, long endTick, long nextTick) {
            this.entity = entity;
            this.endTick = endTick;
            this.nextTick = nextTick;
        }
    }

    private static final class Flock {
        final ServerPlayer owner;
        final ServerLevel level;
        final List<FxBillboard> displays;
        final FlockKind kind;
        final double radius;
        final float heal;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        long nextPulseTick;

        Flock(ServerPlayer owner, ServerLevel level, List<FxBillboard> displays, FlockKind kind,
              double radius, float heal, long endTick, long nextPulseTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.displays = displays;
            this.kind = kind;
            this.radius = radius;
            this.heal = heal;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    private static final class Aura {
        final ServerPlayer owner;
        final ServerLevel level;
        final AuraKind kind;
        final float radius;
        final float heal;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        final @Nullable FxBillboard display;
        /** 已经用过一次「致死复苏」的实体，同一个结界内不重复触发 */
        final Set<UUID> used = new HashSet<>();
        long nextPulseTick;

        Aura(ServerPlayer owner, ServerLevel level, AuraKind kind, float radius, float heal, long endTick,
             long nextPulseTick, String skillId, int cooldownSeconds, @Nullable FxBillboard display) {
            this.owner = owner;
            this.level = level;
            this.kind = kind;
            this.radius = radius;
            this.heal = heal;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.display = display;
        }
    }
}
