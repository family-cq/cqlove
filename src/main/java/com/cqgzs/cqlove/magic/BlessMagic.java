package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.network.MagicCirclePayload;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 祝福系 8 个技能（2026-11-16 设计实现）。
 *
 * <p>四阶沿用需求文档组名：祝福 / 祝喃 / 神印礼赞 / 众星守护，
 * 补齐为 初阶 3 + 中阶 3 + 高阶 1 + 超阶 1。与治愈系「回血」不同，
 * 祝福系定位为<b>纯增益</b>：攻击力、魔力、魔暴击、蓝量上限、双防、再生与负面免疫。</p>
 *
 * <ul>
 *   <li>初阶·祝福：强化（攻击 +12）、圣音（驱散 + 再生 + 防御）、灵力（魔力 +15 / 蓝量 +20）。</li>
 *   <li>中阶·祝喃：强化 / 圣音 / 灵力三种圆圈，跟随玩家，圈内友方获得对应增益 30 秒。</li>
 *   <li>高阶·神印礼赞：跟随领域 30 秒，半径 = 施法范围，圈内友方攻击 +40 / 魔力 +60 /
 *       魔暴击 +20 / 魔暴伤 +50 / 防御 +30%。</li>
 *   <li>超阶·众星守护：跟随结界 60 秒，圈内友方攻击 +100 / 魔力 +100 / 魔暴击 +80 /
 *       魔暴伤 +200 / 防御 +50%，并免疫负面状态与毒系 / 凋零伤害。</li>
 * </ul>
 *
 * <p>增益落地口径：</p>
 * <ul>
 *   <li><b>攻击力</b>：{@link Attributes#ATTACK_DAMAGE} 上的持久属性修饰器（ADD_VALUE），
 *       每 tick 重算「同一实体所受全部祝福的累加值」，无祝福时移除；</li>
 *   <li><b>魔力 / 魔暴击 / 魔暴伤 / 蓝量上限</b>：只对玩家有意义，经
 *       {@link PlayerMagicData#refreshBless} 每 tick 刷新（各祝福累加后写入），停刷后 3 秒自然失效；</li>
 *   <li><b>双防</b>：{@link #incomingMultiplier} 在伤害事件里乘算（防御 +N% = 受伤 ÷(1+N%)）；</li>
 *   <li><b>负面免疫 / 毒伤免疫</b>：{@link #isEffectImmune} 与 {@link #isImmune} 由 MagicEvents 钩子调用；</li>
 *   <li><b>原版药水</b>：力量 / 再生 / 抗性 / 速度 / 幸运，每 1 秒补刷一次。</li>
 * </ul>
 *
 * <p>中阶圆圈与高阶 / 超阶结界走 {@link MagicCircleSync} 客户端渲染层（跟随玩家、本地倒计时）；
 * 超阶圆环由 {@code MagicCaster} 自动开启，{@link #beginTimed} 的技能锁定会把它延长到效果结束。</p>
 */
public final class BlessMagic {

    private BlessMagic() {
    }

    // ---------------- 时长（tick） ----------------
    private static final int STRENGTH_TICKS = 60 * 20;
    private static final int HYMN_TICKS = 30 * 20;
    private static final int SPIRIT_TICKS = 60 * 20;
    private static final int CHANT_TICKS = 30 * 20;
    private static final int SEAL_TICKS = 30 * 20;
    private static final int STARS_TICKS = 60 * 20;
    /** 原版药水效果补刷间隔 */
    private static final int EFFECT_REFRESH = 20;
    /** 自定义属性增益的保留时间：每 tick 由本类刷新，停刷后 3 秒自然失效 */
    private static final int BUFF_KEEPALIVE = 60;

    /** 攻击力加成用的属性修饰器 id（全局唯一，写入各路祝福的累加值） */
    private static final Identifier BLESS_ATTACK_ID =
            Identifier.fromNamespaceAndPath("cqlove", "bless_attack");

    // ---------------- 技能种类 ----------------
    private enum Kind {
        STRENGTH,       // 初阶·强化
        HYMN,           // 初阶·圣音
        SPIRIT,         // 初阶·灵力
        CHANT_STRENGTH, // 中阶·强化
        CHANT_HYMN,     // 中阶·圣音
        CHANT_SPIRIT,   // 中阶·灵力
        SEAL,           // 高阶·神印礼赞
        STARS           // 超阶·众星守护
    }

    /** 单个技能的增益参数集合（聚合时各维度按来源累加，可叠加） */
    private static final class Buff {
        final double attack;
        final double magicPower;
        final double magicCrit;
        final double magicCritDmg;
        final double mana;
        /** 减伤比例（0.30 = 防御 +30% → 受伤 ×0.70） */
        final double defense;
        final int strengthAmp;
        final int regenAmp;
        final int resistanceAmp;
        final int speedAmp;
        final int luckAmp;
        final boolean cleanse;
        final boolean immune;

        Buff(double attack, double magicPower, double magicCrit, double magicCritDmg, double mana,
             double defense, int strengthAmp, int regenAmp, int resistanceAmp, int speedAmp,
             int luckAmp, boolean cleanse, boolean immune) {
            this.attack = attack;
            this.magicPower = magicPower;
            this.magicCrit = magicCrit;
            this.magicCritDmg = magicCritDmg;
            this.mana = mana;
            this.defense = defense;
            this.strengthAmp = strengthAmp;
            this.regenAmp = regenAmp;
            this.resistanceAmp = resistanceAmp;
            this.speedAmp = speedAmp;
            this.luckAmp = luckAmp;
            this.cleanse = cleanse;
            this.immune = immune;
        }
    }

    private static final Buff B_STRENGTH = new Buff(12, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, false, false);
    private static final Buff B_HYMN = new Buff(0, 0, 0, 0, 10, 0, -1, 1, 0, -1, -1, true, false);
    private static final Buff B_SPIRIT = new Buff(0, 15, 10, 0, 20, 0, -1, -1, -1, -1, -1, false, false);
    private static final Buff B_CHANT_STRENGTH = new Buff(20, 0, 0, 0, 0, 0, 1, -1, -1, -1, -1, false, false);
    private static final Buff B_CHANT_HYMN = new Buff(0, 0, 0, 0, 0, 0, -1, 1, 0, 0, -1, true, false);
    private static final Buff B_CHANT_SPIRIT = new Buff(0, 30, 15, 0, 40, 0, -1, -1, -1, -1, -1, false, false);
    private static final Buff B_SEAL = new Buff(40, 60, 20, 50, 30, 0.30D, 2, 1, 0, -1, -1, true, false);
    private static final Buff B_STARS = new Buff(100, 100, 80, 200, 100, 0.50D, 2, 1, 1, 0, 0, true, true);

    private static Buff buffOf(Kind kind) {
        return switch (kind) {
            case STRENGTH -> B_STRENGTH;
            case HYMN -> B_HYMN;
            case SPIRIT -> B_SPIRIT;
            case CHANT_STRENGTH -> B_CHANT_STRENGTH;
            case CHANT_HYMN -> B_CHANT_HYMN;
            case CHANT_SPIRIT -> B_CHANT_SPIRIT;
            case SEAL -> B_SEAL;
            case STARS -> B_STARS;
        };
    }

    /** 众星守护免疫的负面状态 */
    private static final Set<Holder<MobEffect>> IMMUNE_EFFECTS = Set.of(
            MobEffects.POISON, MobEffects.WITHER, MobEffects.HUNGER, MobEffects.NAUSEA,
            MobEffects.BLINDNESS, MobEffects.SLOWNESS, MobEffects.WEAKNESS);

    // ---------------- 运行时状态（不存档） ----------------
    /** 初阶单体增益 */
    private static final List<Bless> BLESSES = new ArrayList<>();
    /** 中阶 / 高阶 / 超阶领域 */
    private static final List<Aura> AURAS = new ArrayList<>();
    /** 每 tick 重建：当前受祝福实体 -> 该实体受到的全部祝福累加增益 */
    private static final Map<LivingEntity, Agg> AGGREGATE = new HashMap<>();
    /** 上一 tick 挂过攻击修饰器的实体（保留实体引用，即使增益状态已移除也能卸修饰器） */
    private static final Map<UUID, LivingEntity> LAST_ATTACK_BUFFED = new HashMap<>();

    // ==================================================================
    // 初阶·祝福（单体）
    // ==================================================================

    /** 强化：对准友方（Ctrl 或没指到友方 = 自己）攻击 +12 + 力量 I，持续 60 秒 */
    public static void strengthen(SkillContext ctx) {
        applySingle(ctx, Kind.STRENGTH, STRENGTH_TICKS);
        MagicDamage.playSound(ctx.level(), fxTargetPos(ctx), SoundEvents.BEACON_ACTIVATE, 0.9F, 1.3F);
    }

    /** 圣音：对准友方驱散全部负面 + 再生 II + 抗性 I + 蓝量上限 +10，持续 30 秒 */
    public static void hymn(SkillContext ctx) {
        applySingle(ctx, Kind.HYMN, HYMN_TICKS);
        MagicDamage.playSound(ctx.level(), fxTargetPos(ctx), SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.5F);
    }

    /** 灵力：对准友方魔力 +15 / 魔暴击 +10 / 蓝量上限 +20，持续 60 秒 */
    public static void spirit(SkillContext ctx) {
        applySingle(ctx, Kind.SPIRIT, SPIRIT_TICKS);
        MagicDamage.playSound(ctx.level(), fxTargetPos(ctx), SoundEvents.BEACON_POWER_SELECT, 1.0F, 1.4F);
    }

    /** 单体技能的目标位置（音效/粒子用），与 applySingle 内部选择口径一致 */
    private static Vec3 fxTargetPos(SkillContext ctx) {
        LivingEntity target = resolveAlly(ctx);
        return target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
    }

    private static void applySingle(SkillContext ctx, Kind kind, int ticks) {
        ServerLevel level = ctx.level();
        LivingEntity target = resolveAlly(ctx);
        long now = level.getGameTime();
        BLESSES.removeIf(b -> b.target == target && b.kind == kind);
        BLESSES.add(new Bless(kind, target, now + ticks));
        Vec3 pos = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        if (target != ctx.player()) {
            MagicDamage.line(level, ctx.origin(), pos, blessParticle(), 0.25D);
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x, pos.y, pos.z,
                22, 0.5D, 0.9D, 0.5D, 0.05D);
        level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z,
                12, 0.4D, 0.8D, 0.4D, 0.03D);
    }

    // ==================================================================
    // 中阶·祝喃（跟随圆圈）
    // ==================================================================

    /** 强化·祝喃：跟随圆圈 30 秒，圈内友方攻击 +20 + 力量 II */
    public static void chantStrength(SkillContext ctx) {
        spawnAura(ctx, Kind.CHANT_STRENGTH, CHANT_TICKS, MagicCirclePayload.BLESS_STRENGTH);
    }

    /** 圣音·祝喃：圈内友方驱散 + 再生 II + 抗性 + 速度，30 秒 */
    public static void chantHymn(SkillContext ctx) {
        spawnAura(ctx, Kind.CHANT_HYMN, CHANT_TICKS, MagicCirclePayload.BLESS_HYMN);
    }

    /** 灵力·祝喃：圈内友方魔力 +30 / 魔暴击 +15 / 蓝量上限 +40，30 秒 */
    public static void chantSpirit(SkillContext ctx) {
        spawnAura(ctx, Kind.CHANT_SPIRIT, CHANT_TICKS, MagicCirclePayload.BLESS_SPIRIT);
    }

    // ==================================================================
    // 高阶 / 超阶
    // ==================================================================

    /** 神印礼赞：跟随领域 30 秒，圈内友方攻击 +40 / 魔力 +60 / 魔暴击 +20 / 魔暴伤 +50 / 防御 +30% */
    public static void divineSeal(SkillContext ctx) {
        spawnAura(ctx, Kind.SEAL, SEAL_TICKS, MagicCirclePayload.BLESS_SEAL);
    }

    /** 众星守护：跟随结界 60 秒，圈内友方全属性大幅增益 + 负面 / 毒伤免疫 */
    public static void starGuardian(SkillContext ctx) {
        spawnAura(ctx, Kind.STARS, STARS_TICKS, MagicCirclePayload.BLESS_STARS);
    }

    private static void spawnAura(SkillContext ctx, Kind kind, int ticks, int circleVariant) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        float radius = kind == Kind.STARS
                ? (float) Math.min(ctx.range(), 14.0D)
                : (float) ctx.range();
        beginTimed(ctx, ticks);
        @Nullable FxBillboard display = null;
        if (kind == Kind.SEAL || kind == Kind.STARS) {
            display = DisplayFx.spawn(level, player.position().add(0.0D, 2.7D, 0.0D),
                    kind == Kind.SEAL ? MagicItems.BLESS_SEAL.get() : MagicItems.BLESS_STARS.get(),
                    1.3F, 1.3F, 1.3F, Display.BillboardConstraints.CENTER);
        }
        AURAS.add(new Aura(kind, player, level, radius, display, circleVariant, now + ticks,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        MagicCircleSync.start(player, circleVariant, ticks, radius);
        Vec3 at = player.position().add(0.0D, 0.15D, 0.0D);
        ColorParticleOption particle = blessParticle();
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2.0D / 24.0D;
            level.sendParticles(particle, at.x + Math.cos(angle) * radius,
                    at.y, at.z + Math.sin(angle) * radius, 4, 0.05D, 0.5D, 0.05D, 0.0D);
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 1.0D, at.z,
                26, 1.0D, 1.2D, 1.0D, 0.05D);
        MagicDamage.playSound(level, at, SoundEvents.BEACON_ACTIVATE, 1.1F, 1.2F);
        MagicDamage.playSound(level, at, SoundEvents.PLAYER_LEVELUP, 0.8F, 1.4F);
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick 调用）
    // ==================================================================

    public static void tickWorld() {
        AGGREGATE.clear();
        long now = -1L;

        // 初阶单体增益
        for (int i = BLESSES.size() - 1; i >= 0; i--) {
            Bless bless = BLESSES.get(i);
            LivingEntity target = bless.target;
            if (!target.isAlive() || target.isRemoved()) {
                BLESSES.remove(i);
                continue;
            }
            now = target.level().getGameTime();
            if (now >= bless.endTick) {
                BLESSES.remove(i);
                continue;
            }
            accumulate(target, bless.kind);
        }

        // 中阶 / 高阶 / 超阶领域
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
            for (LivingEntity ally : alliesInRange(level, owner, owner.position(), aura.radius)) {
                accumulate(ally, aura.kind);
            }
        }

        // 应用到实体：攻击修饰器（累加值）、药水、玩家自定义属性
        boolean refreshEffects = (now % EFFECT_REFRESH) == 0L;
        Map<UUID, LivingEntity> currentAttackBuffed = new HashMap<>();
        ColorParticleOption particle = blessParticle();
        for (Map.Entry<LivingEntity, Agg> entry : AGGREGATE.entrySet()) {
            LivingEntity entity = entry.getKey();
            Agg agg = entry.getValue();
            if (agg.attack > 0.0D) {
                AttributeInstance attack = entity.getAttribute(Attributes.ATTACK_DAMAGE);
                if (attack != null) {
                    attack.addOrUpdateTransientModifier(
                            new AttributeModifier(BLESS_ATTACK_ID, agg.attack, AttributeModifier.Operation.ADD_VALUE));
                    currentAttackBuffed.put(entity.getUUID(), entity);
                }
            }
            if (refreshEffects) {
                applyEffects(entity, agg);
                if (now % 40L == 0L && entity.level() instanceof ServerLevel entityLevel) {
                    Vec3 pos = entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D);
                    entityLevel.sendParticles(particle, pos.x, pos.y, pos.z, 4, 0.3D, 0.5D, 0.3D, 0.02D);
                }
            }
            if (entity instanceof ServerPlayer player) {
                MagicData.get(player).refreshBless(BUFF_KEEPALIVE,
                        agg.magicPower, agg.magicCrit, agg.magicCritDmg, agg.mana);
            }
        }
        // 攻击加成已消失的实体：移除旧修饰器
        for (Map.Entry<UUID, LivingEntity> prev : LAST_ATTACK_BUFFED.entrySet()) {
            if (!currentAttackBuffed.containsKey(prev.getKey())) {
                removeAttackModifier(prev.getValue());
            }
        }
        LAST_ATTACK_BUFFED.clear();
        LAST_ATTACK_BUFFED.putAll(currentAttackBuffed);
    }

    /** 把某技能增益按维度累加进聚合表 */
    private static void accumulate(LivingEntity entity, Kind kind) {
        Buff buff = buffOf(kind);
        Agg agg = AGGREGATE.computeIfAbsent(entity, key -> new Agg());
        // 祝福增益可叠加：所有自定义数值维度按来源累加（攻击/魔力/魔暴/魔暴伤/蓝量/防御）
        agg.attack += buff.attack;
        agg.magicPower += buff.magicPower;
        agg.magicCrit += buff.magicCrit;
        agg.magicCritDmg += buff.magicCritDmg;
        agg.mana += buff.mana;
        agg.defense += buff.defense;
        // 原版药水同类型不可共存（高等级覆盖低等级），等级仍取最高；核心数值已通过上面叠加
        agg.strengthAmp = Math.max(agg.strengthAmp, buff.strengthAmp);
        agg.regenAmp = Math.max(agg.regenAmp, buff.regenAmp);
        agg.resistanceAmp = Math.max(agg.resistanceAmp, buff.resistanceAmp);
        agg.speedAmp = Math.max(agg.speedAmp, buff.speedAmp);
        agg.luckAmp = Math.max(agg.luckAmp, buff.luckAmp);
        agg.cleanse |= buff.cleanse;
        agg.immune |= buff.immune;
    }

    private static void applyEffects(LivingEntity entity, Agg agg) {
        if (agg.cleanse) {
            cleanseEntity(entity);
        }
        if (agg.strengthAmp >= 0) {
            entity.addEffect(new MobEffectInstance(MobEffects.STRENGTH, EFFECT_REFRESH + 20, agg.strengthAmp));
        }
        if (agg.regenAmp >= 0) {
            entity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_REFRESH + 20, agg.regenAmp));
        }
        if (agg.resistanceAmp >= 0) {
            entity.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, EFFECT_REFRESH + 20, agg.resistanceAmp));
        }
        if (agg.speedAmp >= 0) {
            entity.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_REFRESH + 20, agg.speedAmp));
        }
        if (agg.luckAmp >= 0) {
            entity.addEffect(new MobEffectInstance(MobEffects.LUCK, EFFECT_REFRESH + 20, agg.luckAmp));
        }
    }

    // ==================================================================
    // 伤害事件：防御增益 / 负面与毒伤免疫
    // ==================================================================

    /** 与其它系列乘算叠加：受祝福实体的防御增益（防御 +N% = 受伤 ÷(1+N%)） */
    public static float incomingMultiplier(LivingEntity entity, boolean magic) {
        double defense = 0.0D;
        for (Bless bless : BLESSES) {
            if (bless.target == entity) {
                defense += buffOf(bless.kind).defense;
            }
        }
        for (Aura aura : AURAS) {
            if (aura.appliesTo(entity)) {
                defense += buffOf(aura.kind).defense;
            }
        }
        return defense <= 0.0D ? 1.0F : (float) (1.0D / (1.0D + defense));
    }

    /** 众星守护结界内：免疫中毒 / 凋零 / 饥饿 / 反胃 / 失明 / 缓慢 / 虚弱 */
    public static boolean isEffectImmune(LivingEntity entity, Holder<MobEffect> effect) {
        if (!IMMUNE_EFFECTS.contains(effect)) {
            return false;
        }
        for (Aura aura : AURAS) {
            if (aura.kind == Kind.STARS && aura.appliesTo(entity)) {
                return true;
            }
        }
        return false;
    }

    /** 众星守护结界内：免疫毒系魔法伤害与凋零伤害 */
    public static boolean isImmune(LivingEntity entity, DamageSource source) {
        boolean stars = false;
        for (Aura aura : AURAS) {
            if (aura.kind == Kind.STARS && aura.appliesTo(entity)) {
                stars = true;
                break;
            }
        }
        if (!stars) {
            return false;
        }
        return source.is(MagicDamageTypes.key(MagicElement.POISON)) || source.is(DamageTypes.WITHER);
    }

    // ==================================================================
    // 清理
    // ==================================================================

    /** 玩家下线 / 死亡：清掉该玩家施加的领域、加在自己身上的增益与攻击修饰器 */
    public static void removePlayer(ServerPlayer player) {
        BLESSES.removeIf(b -> b.target == player);
        for (int i = AURAS.size() - 1; i >= 0; i--) {
            if (AURAS.get(i).owner == player) {
                discardAura(AURAS.remove(i));
            }
        }
        removeAttackModifier(player);
        MagicData.get(player).clearBless();
        MagicCircleSync.stop(player, MagicCirclePayload.BLESS_STRENGTH);
        MagicCircleSync.stop(player, MagicCirclePayload.BLESS_HYMN);
        MagicCircleSync.stop(player, MagicCirclePayload.BLESS_SPIRIT);
        MagicCircleSync.stop(player, MagicCirclePayload.BLESS_SEAL);
        MagicCircleSync.stop(player, MagicCirclePayload.BLESS_STARS);
    }

    /** 服务器关闭：全部收干净 */
    public static void clearAll() {
        for (Bless bless : BLESSES) {
            removeAttackModifier(bless.target);
        }
        BLESSES.clear();
        for (Aura aura : AURAS) {
            if (aura.display != null) {
                aura.display.discard();
            }
        }
        AURAS.clear();
        AGGREGATE.clear();
        LAST_ATTACK_BUFFED.clear();
    }

    private static void removeAttackModifier(LivingEntity entity) {
        AttributeInstance attack = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.removeModifier(BLESS_ATTACK_ID);
        }
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

    /** 目标选择：按住 Ctrl 或没指到友方时作用于自己，绝不误给敌人加增益 */
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

    private static List<LivingEntity> alliesInRange(ServerLevel level, ServerPlayer owner,
                                                    Vec3 center, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            // 用 friendly 判定：PVP 开启时其他玩家仍算友方，照样能吃领域增益
            if (!friendly(owner, entity) || !within(center, entity.position(), radius)) {
                continue;
            }
            result.add(entity);
        }
        return result;
    }

    private static boolean friendly(ServerPlayer owner, LivingEntity entity) {
        // 其他玩家永远是合法增益目标（不受 PVP 开关影响）
        return CombatTracker.isAllyTarget(owner, entity);
    }

    private static boolean hostile(ServerPlayer owner, LivingEntity entity) {
        return CombatTracker.isEnemyTarget(owner, entity);
    }

    private static boolean within(Vec3 center, Vec3 pos, double radius) {
        double dx = pos.x - center.x;
        double dz = pos.z - center.z;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** 驱散全部负面状态 */
    private static void cleanseEntity(LivingEntity entity) {
        for (MobEffectInstance effect : new ArrayList<>(entity.getActiveEffects())) {
            if (!effect.getEffect().value().isBeneficial()) {
                entity.removeEffect(effect.getEffect());
            }
        }
    }

    private static ColorParticleOption blessParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.BLESS.color());
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

    // ---------------- 状态记录 ----------------

    private static final class Bless {
        final Kind kind;
        final LivingEntity target;
        final long endTick;

        Bless(Kind kind, LivingEntity target, long endTick) {
            this.kind = kind;
            this.target = target;
            this.endTick = endTick;
        }
    }

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

        Aura(Kind kind, ServerPlayer owner, ServerLevel level, float radius,
             @Nullable FxBillboard display, int circleVariant, long endTick,
             String skillId, int cooldownSeconds) {
            this.kind = kind;
            this.owner = owner;
            this.level = level;
            this.radius = radius;
            this.display = display;
            this.circleVariant = circleVariant;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }

        boolean appliesTo(LivingEntity entity) {
            if (!entity.isAlive() || entity.level() != level || !friendly(owner, entity)) {
                return false;
            }
            return within(owner.position(), entity.position(), radius);
        }
    }

    /** 单个实体受到的所有祝福增益的聚合（各维度按来源累加） */
    private static final class Agg {
        double attack;
        double magicPower;
        double magicCrit;
        double magicCritDmg;
        double mana;
        double defense;
        int strengthAmp = -1;
        int regenAmp = -1;
        int resistanceAmp = -1;
        int speedAmp = -1;
        int luckAmp = -1;
        boolean cleanse;
        boolean immune;
    }
}
