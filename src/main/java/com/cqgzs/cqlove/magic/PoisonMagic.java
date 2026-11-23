package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
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
 * 毒系 8 个技能（2026-11-14 设计实现）。
 *
 * <p>四阶沿用需求文档的组名与技能名：毒萃（毒牙 / 吐纳 / 炼化）、
 * 毒穴（自爆毒 / 泛发感染 / 寄生毒）、毒变、万噬之风，
 * 并按其它已实现系列的规格补齐为 初阶 3 + 中阶 3 + 高阶 1 + 超阶 1。</p>
 *
 * <ul>
 *   <li>初阶·毒萃：毒牙（单体即时 3 点 + 2 秒腐蚀每秒 1 点，防御 -10%）、吐纳（准星落点毒雾云 10 秒）、
 *       炼化（10 秒百毒不侵 + 毒系伤害 +30% + 身边 3~5 格毒气每秒 1 点）。</li>
 *   <li>中阶·毒穴：自爆毒（8 秒后爆裂 3 格溅射）、泛发感染（12 秒，每 2 秒传播最多 3 跳）、
 *       寄生毒（20 秒吸血 + 缓慢 II / 虚弱 II，宿主死亡回血并留下毒云）。</li>
 *   <li>高阶·毒变：跟随玩家的毒变领域 30 秒，半径 = 施法范围，圈内敌对每秒 100 点毒系伤害、
 *       中毒 III、双防 -30%，自身毒系伤害 +50% 且免疫负面。</li>
 *   <li>超阶·万噬之风：跟随玩家的结界 60 秒，圈内敌对每秒 500 点毒系伤害，
 *       每存活 1 秒叠加 1 层「噬毒」（最多 10 层，每层 +15 点，满层引爆后重置），
 *       双防 -50%，友方每秒回 30 点，自身毒系伤害 +100% 且免疫负面状态
 *       （中毒/凋零/饥饿/反胃/失明/缓慢/虚弱 + 毒系伤害）。</li>
 * </ul>
 *
 * <p><b>伤害结算口径</b>：所有 DOT / 领域 / 溅射伤害先按「施法者 + 目标」聚合，
 * 每 tick 对每个目标只调用一次 {@link MagicDamage#magicDamage}，避免同一 tick 内
 * 多层毒伤被原版 {@code hurtTime} 无敌帧吞掉；结算时统一乘上施法者当前的
 * 炼化 / 毒变 / 万噬之风加成（取最大值，不叠乘）。</p>
 *
 * <p>领域圈走 {@link MagicCircleSync} 的客户端渲染层（只在开启/结束各发一次状态包，
 * 跟随玩家、本地倒计时），不生成贴地实体、不每 tick 发包。超阶圆环由 {@code MagicCaster}
 * 自动开启，{@link #beginTimed} 的技能锁定会把它延长到效果结束。</p>
 *
 * <p>防御口径与其它系一致：「防御 -N%」= 受伤 ×(1+N%)，在 {@code MagicEvents} 的伤害事件里乘算。</p>
 */
public final class PoisonMagic {

    private PoisonMagic() {
    }

    // ---------------- 时长（tick） ----------------
    private static final int FANG_TICKS = 2 * 20;
    private static final int CLOUD_TICKS = 10 * 20;
    private static final int ALCHEMY_TICKS = 10 * 20;
    private static final int BURST_TICKS = 8 * 20;
    private static final int INFECTION_TICKS = 12 * 20;
    private static final int PARASITE_TICKS = 20 * 20;
    private static final int MUTATION_TICKS = 30 * 20;
    private static final int GALE_TICKS = 60 * 20;
    /** 所有持续伤害每秒结算一次 */
    private static final int DOT_TICKS = 20;
    /** 泛发感染每 2 秒传播一次 */
    private static final int SPREAD_TICKS = 40;
    /** 寄生毒宿主死亡后留下的小毒云时长 */
    private static final int LINGER_TICKS = 6 * 20;

    // ---------------- 每跳伤害（毒系魔法伤害） ----------------
    private static final double FANG_HIT = 3.0D;
    private static final double FANG_DOT = 1.0D;
    private static final double CLOUD_DPS = 6.0D;
    private static final double ALCHEMY_DPS = 1.0D;
    private static final double BURST_DOT = 5.0D;
    private static final double BURST_BLAST = 30.0D;
    private static final double INFECTION_DPS = 8.0D;
    private static final double PARASITE_DPS = 12.0D;
    private static final double PARASITE_LEECH = 6.0D;
    private static final double PARASITE_DEATH_HEAL = 20.0D;
    private static final double MUTATION_DPS = 100.0D;
    private static final double GALE_DPS = 500.0D;
    private static final double GALE_STACK_DPS = 15.0D;
    private static final int GALE_MAX_STACK = 10;
    private static final double GALE_STACK_BLAST = 100.0D;
    private static final double GALE_ALLY_HEAL = 30.0D;

    // ---------------- 减防（受伤倍率）与增伤 ----------------
    private static final double FANG_DEF = 1.10D;
    private static final double MUTATION_DEF = 1.30D;
    private static final double GALE_DEF = 1.50D;
    private static final double ALCHEMY_BONUS = 1.30D;
    private static final double MUTATION_BONUS = 1.50D;
    private static final double GALE_BONUS = 2.00D;

    /** 感染传播：最多 3 跳，每跳伤害 ×0.8 */
    private static final int INFECTION_MAX_JUMP = 3;
    private static final double INFECTION_DECAY = 0.8D;
    /** 局部溅射 / 传播半径（固定值，与施法范围无关） */
    private static final double BLAST_RADIUS = 3.0D;
    private static final double SPREAD_RADIUS = 4.0D;

    // ---------------- 运行时状态（不存档） ----------------
    private enum VenomKind { FANG, BURST, INFECTION, PARASITE }
    private enum AuraKind { MUTATION, GALE }

    /** 施加在生物身上的各种毒 */
    private static final List<Venom> VENOMS = new ArrayList<>();
    /** 固定在落点的毒雾云（吐纳 / 寄生毒死亡残留） */
    private static final List<Cloud> CLOUDS = new ArrayList<>();
    /** 炼化：自身增益与身边毒气 */
    private static final List<Alchemy> ALCHEMIES = new ArrayList<>();
    /** 毒变 / 万噬之风：跟随玩家的领域 */
    private static final List<Aura> AURAS = new ArrayList<>();

    /** 每 tick 的待结算伤害：施法者 -> (目标 -> 本 tick 累计伤害) */
    private record DamageBatch(Map<ServerPlayer, Map<LivingEntity, Double>> byCaster) {
    }

    // ==================================================================
    // 初阶·毒萃
    // ==================================================================

    /** 毒牙：准星单体立即 3 点毒系伤害 + 2 秒腐蚀（每秒 1 点、防御 -10%） */
    public static void fang(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = resolveHostileTarget(ctx, 1.5D);
        if (target == null) {
            failCast(ctx, "毒牙失败: 准星没有指向敌对生物");
            return;
        }
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, poisonParticle(), 0.25D);
        // 立即伤害同样走统一魔法管线（带魔力 / 元素精通 / 魔暴击 / 伤害数字）
        ctx.damage(target, FANG_HIT * damageBonus(player));
        long now = level.getGameTime();
        addVenom(new Venom(VenomKind.FANG, player, level, target, now + FANG_TICKS,
                now + DOT_TICKS, FANG_DOT, 0, new HashSet<>(Set.of(target.getUUID()))));
        level.sendParticles(ParticleTypes.SNEEZE, hit.x, hit.y, hit.z,
                12, 0.35D, 0.4D, 0.35D, 0.05D);
        pulseParticles(level, hit, 16);
        MagicDamage.playSound(level, hit, SoundEvents.BREWING_STAND_BREW, 0.9F, 0.7F);
    }

    /** 吐纳：在准星落点呼出一团毒雾云，10 秒内每秒灼烧半径内敌对 */
    public static void breath(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        // 半径随施法范围提升（初阶 3.3 格，高阶以上封顶 6 格），不写死格数
        double radius = Math.min(6.0D, Math.max(3.0D, ctx.range() / 3.0D));
        FxBillboard display = DisplayFx.spawn(level, center.add(0.0D, 0.9D, 0.0D),
                MagicItems.POISON_CLOUD.get(), 1.6F, 1.6F, 1.6F, Display.BillboardConstraints.CENTER);
        long now = level.getGameTime();
        beginTimed(ctx, CLOUD_TICKS);
        CLOUDS.add(new Cloud(player, level, center, radius, display, now + CLOUD_TICKS,
                now + DOT_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), true));
        ringParticles(level, center, radius, 20);
        pulseParticles(level, center.add(0.0D, 0.8D, 0.0D), 24);
        MagicDamage.playSound(level, center, SoundEvents.BREWING_STAND_BREW, 1.0F, 0.55F);
    }

    /** 炼化：10 秒百毒不侵，毒系伤害 +30%，身边 3~5 格毒气每秒 1 点灼烧敌对 */
    public static void refine(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        double radius = Math.min(5.0D, Math.max(3.0D, ctx.range() / 4.0D));
        long now = level.getGameTime();
        beginTimed(ctx, ALCHEMY_TICKS);
        ALCHEMIES.removeIf(state -> state.owner == player);
        ALCHEMIES.add(new Alchemy(player, level, radius, now + ALCHEMY_TICKS, now + DOT_TICKS,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        clearNegative(player);
        Vec3 at = player.position().add(0.0D, 1.0D, 0.0D);
        ringParticles(level, player.position(), radius, 16);
        pulseParticles(level, at, 28);
        MagicDamage.playSound(level, at, SoundEvents.BEACON_ACTIVATE, 0.9F, 0.6F);
        MagicDamage.playSound(level, at, SoundEvents.BREWING_STAND_BREW, 0.8F, 1.2F);
    }

    // ==================================================================
    // 中阶·毒穴
    // ==================================================================

    /** 自爆毒：植入毒爆种子 8 秒，每秒 5 点，到期或宿主死亡时 3 格溅射 30 点 */
    public static void burst(SkillContext ctx) {
        LivingEntity target = resolveHostileTarget(ctx, 1.5D);
        if (target == null) {
            failCast(ctx, "自爆毒失败: 准星没有指向敌对生物");
            return;
        }
        long now = ctx.level().getGameTime();
        addVenom(new Venom(VenomKind.BURST, ctx.player(), ctx.level(), target, now + BURST_TICKS,
                now + DOT_TICKS, BURST_DOT, 0, new HashSet<>(Set.of(target.getUUID()))));
        Vec3 at = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
        MagicDamage.line(ctx.level(), ctx.origin(), at, poisonParticle(), 0.25D);
        pulseParticles(ctx.level(), at, 20);
        MagicDamage.playSound(ctx.level(), at, SoundEvents.EVOKER_CAST_SPELL, 0.8F, 0.5F);
    }

    /** 泛发感染：12 秒每秒 8 点，每 2 秒向 4 格内最多 2 只未感染敌对传播（最多 3 跳） */
    public static void infection(SkillContext ctx) {
        LivingEntity target = resolveHostileTarget(ctx, 1.5D);
        if (target == null) {
            failCast(ctx, "泛发感染失败: 准星没有指向敌对生物");
            return;
        }
        long now = ctx.level().getGameTime();
        Set<UUID> chain = new HashSet<>(Set.of(target.getUUID()));
        addVenom(new Venom(VenomKind.INFECTION, ctx.player(), ctx.level(), target, now + INFECTION_TICKS,
                now + DOT_TICKS, INFECTION_DPS, 0, chain));
        Vec3 at = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
        MagicDamage.line(ctx.level(), ctx.origin(), at, poisonParticle(), 0.25D);
        pulseParticles(ctx.level(), at, 22);
        MagicDamage.playSound(ctx.level(), at, SoundEvents.BREWING_STAND_BREW, 0.9F, 0.5F);
    }

    /** 寄生毒：20 秒每秒 12 点并吸血 6 点，缓慢 II + 虚弱 II，宿主死亡回 20 点并留下毒云 */
    public static void parasite(SkillContext ctx) {
        LivingEntity target = resolveHostileTarget(ctx, 1.5D);
        if (target == null) {
            failCast(ctx, "寄生毒失败: 准星没有指向敌对生物");
            return;
        }
        long now = ctx.level().getGameTime();
        addVenom(new Venom(VenomKind.PARASITE, ctx.player(), ctx.level(), target, now + PARASITE_TICKS,
                now + DOT_TICKS, PARASITE_DPS, 0, new HashSet<>(Set.of(target.getUUID()))));
        Vec3 at = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
        MagicDamage.line(ctx.level(), ctx.origin(), at, poisonParticle(), 0.25D);
        pulseParticles(ctx.level(), at, 24);
        MagicDamage.playSound(ctx.level(), at, SoundEvents.EVOKER_CAST_SPELL, 0.9F, 0.4F);
    }

    // ==================================================================
    // 高阶 / 超阶
    // ==================================================================

    /** 毒变：跟随玩家的领域 30 秒，半径 = 施法范围，圈内敌对每秒 100 点 + 双防 -30% */
    public static void mutation(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        float radius = (float) ctx.range();
        long now = level.getGameTime();
        beginTimed(ctx, MUTATION_TICKS);
        AURAS.add(new Aura(player, level, AuraKind.MUTATION, radius, now + MUTATION_TICKS,
                now + DOT_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        MagicCircleSync.start(player, MagicCirclePayload.POISON_MUTATION, MUTATION_TICKS, radius);
        Vec3 at = player.position();
        ringParticles(level, at, radius, 24);
        pulseParticles(level, at.add(0.0D, 1.0D, 0.0D), 36);
        MagicDamage.playSound(level, at, SoundEvents.BEACON_ACTIVATE, 1.1F, 0.55F);
        MagicDamage.playSound(level, at, SoundEvents.BREWING_STAND_BREW, 1.0F, 0.5F);
    }

    /** 万噬之风：60 秒结界，每秒 500 点 + 噬毒层数，双防 -50%，友方每秒回 30 点，施法者免疫负面状态 */
    public static void devouringGale(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        // 与治愈系「复苏」同口径：结界是自身周围的固有尺寸，不按超阶 30 格铺满整张地图
        float radius = (float) Math.min(ctx.range(), 14.0D);
        long now = level.getGameTime();
        beginTimed(ctx, GALE_TICKS);
        AURAS.add(new Aura(player, level, AuraKind.GALE, radius, now + GALE_TICKS,
                now + DOT_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        MagicCircleSync.start(player, MagicCirclePayload.POISON_GALE, GALE_TICKS, radius);
        Vec3 at = player.position();
        ringParticles(level, at, radius, 32);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1.0D, at.z,
                2, 0.4D, 0.4D, 0.4D, 0.0D);
        pulseParticles(level, at.add(0.0D, 1.2D, 0.0D), 48);
        MagicDamage.playSound(level, at, SoundEvents.TOTEM_USE, 1.2F, 0.7F);
        MagicDamage.playSound(level, at, SoundEvents.EVOKER_CAST_SPELL, 1.2F, 0.45F);
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick 调用）
    // ==================================================================

    public static void tickWorld() {
        DamageBatch batch = new DamageBatch(new HashMap<>());
        tickVenoms(batch);
        tickClouds(batch);
        tickAlchemies(batch);
        tickAuras(batch);
        flush(batch);
    }

    private static void tickVenoms(DamageBatch batch) {
        for (int i = VENOMS.size() - 1; i >= 0; i--) {
            Venom venom = VENOMS.get(i);
            LivingEntity target = venom.target;
            long now = venom.level.getGameTime();
            if (!target.isAlive() || target.isRemoved()) {
                onHostGone(venom, batch);
                VENOMS.remove(i);
                continue;
            }
            if (now >= venom.endTick) {
                if (venom.kind == VenomKind.BURST) {
                    explodeBurst(venom, batch);
                }
                VENOMS.remove(i);
                continue;
            }
            if (now >= venom.nextPulseTick) {
                venom.nextPulseTick = now + DOT_TICKS;
                addDamage(batch, venom.owner, target, venom.damage);
                applyVenomEffects(venom, target);
                pulseParticles(venom.level, target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D), 6);
                if (venom.kind == VenomKind.PARASITE && venom.owner.isAlive()) {
                    venom.owner.heal((float) PARASITE_LEECH);
                }
            }
            if (venom.kind == VenomKind.INFECTION && now >= venom.nextSpreadTick) {
                venom.nextSpreadTick = now + SPREAD_TICKS;
                spreadInfection(venom);
            }
        }
    }

    /** 宿主提前死亡：自爆毒立即引爆；寄生毒回血并留下毒云；其余安静结束 */
    private static void onHostGone(Venom venom, DamageBatch batch) {
        if (venom.kind == VenomKind.BURST) {
            explodeBurst(venom, batch);
        } else if (venom.kind == VenomKind.PARASITE) {
            if (venom.owner.isAlive()) {
                venom.owner.heal((float) PARASITE_DEATH_HEAL);
            }
            spawnLingeringCloud(venom);
        }
    }

    /** 自爆毒收尾：3 格内敌对受 30 点毒系伤害 + 中毒 II 6 秒（不破坏地形） */
    private static void explodeBurst(Venom venom, DamageBatch batch) {
        ServerLevel level = venom.level;
        Vec3 center = venom.target.position();
        for (LivingEntity entity : hostilesInRange(level, venom.owner, center, BLAST_RADIUS)) {
            if (entity == venom.target) {
                continue;
            }
            addDamage(batch, venom.owner, entity, BURST_BLAST);
            entity.addEffect(new MobEffectInstance(MobEffects.POISON, 120, 1));
        }
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.8D, center.z,
                12, 1.0D, 0.8D, 1.0D, 0.08D);
        level.sendParticles(poisonParticle(), center.x, center.y + 0.9D, center.z,
                48, 1.2D, 0.9D, 1.2D, 0.1D);
        level.sendParticles(ParticleTypes.SNEEZE, center.x, center.y + 0.9D, center.z,
                20, 1.0D, 0.7D, 1.0D, 0.05D);
        MagicDamage.playSound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.1F, 0.7F);
    }

    /** 泛发感染：向 4 格内最多 2 只未感染敌对传播，每跳伤害 ×0.8，最多 3 跳 */
    private static void spreadInfection(Venom source) {
        if (source.generation >= INFECTION_MAX_JUMP) {
            return;
        }
        ServerLevel level = source.level;
        Vec3 center = source.target.position();
        int spread = 0;
        for (LivingEntity entity : hostilesInRange(level, source.owner, center, SPREAD_RADIUS)) {
            if (spread >= 2 || source.infected.contains(entity.getUUID())) {
                continue;
            }
            source.infected.add(entity.getUUID());
            long now = level.getGameTime();
            // 倒序遍历中追加到列表末尾是安全的（新增项索引 > 当前 i）
            VENOMS.add(new Venom(VenomKind.INFECTION, source.owner, level, entity,
                    Math.min(source.endTick, now + INFECTION_TICKS), now + DOT_TICKS,
                    source.damage * INFECTION_DECAY, source.generation + 1, source.infected));
            MagicDamage.line(level, center.add(0.0D, 0.6D, 0.0D),
                    entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D), poisonParticle(), 0.4D);
            spread++;
        }
    }

    /** 寄生毒宿主死亡：原地留下一小片 3 格 / 6 秒毒云（不锁定技能、不再进冷却） */
    private static void spawnLingeringCloud(Venom venom) {
        Vec3 center = venom.target.position();
        long now = venom.level.getGameTime();
        FxBillboard display = DisplayFx.spawn(venom.level, center.add(0.0D, 0.7D, 0.0D),
                MagicItems.POISON_CLOUD.get(), 1.0F, 1.0F, 1.0F, Display.BillboardConstraints.CENTER);
        CLOUDS.add(new Cloud(venom.owner, venom.level, center, BLAST_RADIUS, display,
                now + LINGER_TICKS, now + DOT_TICKS, "", 0, false));
        pulseParticles(venom.level, center.add(0.0D, 0.7D, 0.0D), 24);
    }

    private static void tickClouds(DamageBatch batch) {
        for (int i = CLOUDS.size() - 1; i >= 0; i--) {
            Cloud cloud = CLOUDS.get(i);
            long now = cloud.level.getGameTime();
            if (now >= cloud.endTick || !cloud.owner.isAlive()) {
                discardCloud(cloud);
                if (cloud.timed) {
                    finishTimed(cloud.owner, cloud.skillId, cloud.cooldownSeconds, now);
                }
                CLOUDS.remove(i);
                continue;
            }
            if (now >= cloud.nextPulseTick) {
                cloud.nextPulseTick = now + DOT_TICKS;
                for (LivingEntity entity : hostilesInRange(cloud.level, cloud.owner, cloud.center, cloud.radius)) {
                    addDamage(batch, cloud.owner, entity, CLOUD_DPS);
                    entity.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 0));
                    entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
                }
                if (now % 40L == 0L) {
                    ringParticles(cloud.level, cloud.center, cloud.radius, 12);
                }
            }
        }
    }

    private static void tickAlchemies(DamageBatch batch) {
        for (int i = ALCHEMIES.size() - 1; i >= 0; i--) {
            Alchemy state = ALCHEMIES.get(i);
            long now = state.level.getGameTime();
            if (now >= state.endTick || !state.owner.isAlive()) {
                finishTimed(state.owner, state.skillId, state.cooldownSeconds, now);
                ALCHEMIES.remove(i);
                continue;
            }
            if (now >= state.nextPulseTick) {
                state.nextPulseTick = now + DOT_TICKS;
                clearNegative(state.owner);
                Vec3 center = state.owner.position();
                for (LivingEntity entity : hostilesInRange(state.level, state.owner, center, state.radius)) {
                    addDamage(batch, state.owner, entity, ALCHEMY_DPS);
                }
                if (now % 40L == 0L) {
                    ringParticles(state.level, center, state.radius, 10);
                }
            }
        }
    }

    private static void tickAuras(DamageBatch batch) {
        for (int i = AURAS.size() - 1; i >= 0; i--) {
            Aura aura = AURAS.get(i);
            ServerLevel level = aura.level;
            ServerPlayer owner = aura.owner;
            long now = level.getGameTime();
            if (now >= aura.endTick || !owner.isAlive()) {
                discardAura(aura);
                finishTimed(owner, aura.skillId, aura.cooldownSeconds, now);
                AURAS.remove(i);
                continue;
            }
            if (now < aura.nextPulseTick) {
                continue;
            }
            aura.nextPulseTick = now + DOT_TICKS;
            clearNegative(owner);
            Vec3 center = owner.position();
            boolean gale = aura.kind == AuraKind.GALE;
            for (LivingEntity entity : hostilesInRange(level, owner, center, aura.radius)) {
                double damage = gale ? galeDamage(aura, entity) : MUTATION_DPS;
                addDamage(batch, owner, entity, damage);
                entity.addEffect(new MobEffectInstance(MobEffects.POISON, 40, gale ? 2 : 2));
                entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, gale ? 1 : 0));
            }
            if (gale) {
                // 吞噬来的生命力分给圈内友方
                for (LivingEntity ally : alliesInRange(level, owner, center, aura.radius)) {
                    ally.heal((float) GALE_ALLY_HEAL);
                }
            }
            if (now % 40L == 0L) {
                ringParticles(level, center, aura.radius, gale ? 28 : 18);
            }
        }
    }

    /** 万噬之风：每存活 1 秒 +1 层，最多 10 层；满层引爆一次 100 点后清零重来 */
    private static double galeDamage(Aura aura, LivingEntity entity) {
        UUID id = entity.getUUID();
        int stacks = aura.stacks.getOrDefault(id, 0) + 1;
        if (stacks > GALE_MAX_STACK) {
            stacks = 1;
            Vec3 at = entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D);
            aura.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z,
                    4, 0.4D, 0.4D, 0.4D, 0.05D);
            pulseParticles(aura.level, at, 16);
        }
        aura.stacks.put(id, stacks);
        return GALE_DPS + (stacks - 1) * GALE_STACK_DPS +
                (stacks == GALE_MAX_STACK ? GALE_STACK_BLAST : 0.0D);
    }

    /** 把本 tick 聚合后的伤害一次性结算，避免多层毒伤被无敌帧吞掉 */
    private static void flush(DamageBatch batch) {
        for (Map.Entry<ServerPlayer, Map<LivingEntity, Double>> caster : batch.byCaster().entrySet()) {
            ServerPlayer owner = caster.getKey();
            for (Map.Entry<LivingEntity, Double> hit : caster.getValue().entrySet()) {
                LivingEntity target = hit.getKey();
                if (target == owner || !target.isAlive() || target.isRemoved()) {
                    continue;
                }
                MagicDamage.magicDamage(owner, target, hit.getValue(), MagicElement.POISON);
            }
        }
    }

    private static void addDamage(DamageBatch batch, ServerPlayer owner, LivingEntity target, double base) {
        if (owner == null || target == null || target == owner || !target.isAlive() || target.isRemoved()) {
            return;
        }
        batch.byCaster().computeIfAbsent(owner, key -> new HashMap<>())
                .merge(target, base * damageBonus(owner), Double::sum);
    }

    /** 施法者当前的毒系伤害加成（炼化 +30% / 毒变 +50% / 万噬之风 +100%，取最高不叠乘） */
    private static double damageBonus(ServerPlayer owner) {
        double bonus = 1.0D;
        for (Alchemy state : ALCHEMIES) {
            if (state.owner == owner) {
                bonus = Math.max(bonus, ALCHEMY_BONUS);
            }
        }
        for (Aura aura : AURAS) {
            if (aura.owner == owner) {
                bonus = Math.max(bonus, aura.kind == AuraKind.MUTATION ? MUTATION_BONUS : GALE_BONUS);
            }
        }
        return bonus;
    }

    private static void addVenom(Venom venom) {
        // 同一施法者对同一目标的同类毒只保留一份，避免连点刷出上百个状态
        VENOMS.removeIf(existing -> existing.owner == venom.owner && existing.target == venom.target
                && existing.kind == venom.kind);
        VENOMS.add(venom);
    }

    private static void applyVenomEffects(Venom venom, LivingEntity target) {
        switch (venom.kind) {
            case FANG -> target.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 1));
            case BURST -> target.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 1));
            case INFECTION -> {
                target.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 1));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0));
            }
            case PARASITE -> {
                target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1));
            }
        }
    }

    // ==================================================================
    // 伤害事件：减防与自身免疫
    // ==================================================================

    /** 毒牙 -10% / 毒变 -30% / 万噬之风 -50%：受伤倍率，与其它系列乘算叠加 */
    public static float incomingMultiplier(LivingEntity entity) {
        double multiplier = 1.0D;
        for (Venom venom : VENOMS) {
            if (venom.kind == VenomKind.FANG && venom.target == entity) {
                multiplier *= FANG_DEF;
            }
        }
        for (Aura aura : AURAS) {
            if (aura.owner == entity || !hostile(aura.owner, entity)
                    || !within(aura.owner.position(), entity.position(), aura.radius)) {
                continue;
            }
            multiplier *= aura.kind == AuraKind.MUTATION ? MUTATION_DEF : GALE_DEF;
        }
        return (float) multiplier;
    }

    /** 炼化 / 毒变 / 万噬之风期间：本人免疫毒系魔法伤害与凋零类伤害 */
    public static boolean isImmune(ServerPlayer player, DamageSource source) {
        if (!hasSelfBuff(player)) {
            return false;
        }
        return source.is(MagicDamageTypes.key(MagicElement.POISON))
                || source.is(DamageTypes.WITHER);
    }

    /** 万噬之风期间：施法者免疫中毒/凋零/饥饿/反胃/失明/缓慢/虚弱等负面状态（由 MagicEvents 的 MobEffectEvent 钩子拦截） */
    private static final Set<Holder<MobEffect>> GALE_IMMUNE_EFFECTS = Set.of(
            MobEffects.POISON, MobEffects.WITHER, MobEffects.HUNGER, MobEffects.NAUSEA,
            MobEffects.BLINDNESS, MobEffects.SLOWNESS, MobEffects.WEAKNESS);

    public static boolean isEffectImmune(ServerPlayer player, Holder<MobEffect> effect) {
        if (!hasGaleAura(player)) {
            return false;
        }
        return GALE_IMMUNE_EFFECTS.contains(effect);
    }

    private static boolean hasGaleAura(ServerPlayer player) {
        for (Aura aura : AURAS) {
            if (aura.owner == player && aura.kind == AuraKind.GALE) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSelfBuff(ServerPlayer player) {
        for (Alchemy state : ALCHEMIES) {
            if (state.owner == player) {
                return true;
            }
        }
        for (Aura aura : AURAS) {
            if (aura.owner == player) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 清理
    // ==================================================================

    /** 玩家下线 / 死亡：清掉该玩家施加的全部毒、领域与圆圈 */
    public static void removePlayer(ServerPlayer player) {
        VENOMS.removeIf(state -> state.owner == player || state.target == player);
        for (int i = CLOUDS.size() - 1; i >= 0; i--) {
            if (CLOUDS.get(i).owner == player) {
                discardCloud(CLOUDS.remove(i));
            }
        }
        ALCHEMIES.removeIf(state -> state.owner == player);
        for (int i = AURAS.size() - 1; i >= 0; i--) {
            if (AURAS.get(i).owner == player) {
                discardAura(AURAS.remove(i));
            }
        }
        MagicCircleSync.stop(player, MagicCirclePayload.POISON_MUTATION);
        MagicCircleSync.stop(player, MagicCirclePayload.POISON_GALE);
    }

    /** 服务器关闭：全部收干净（关服时不发包，客户端退出世界会自己清缓存） */
    public static void clearAll() {
        VENOMS.clear();
        for (Cloud cloud : CLOUDS) {
            if (cloud.display != null) {
                cloud.display.discard();
            }
        }
        CLOUDS.clear();
        ALCHEMIES.clear();
        AURAS.clear();
    }

    private static void discardCloud(Cloud cloud) {
        if (cloud.display != null) {
            cloud.display.discard();
        }
    }

    private static void discardAura(Aura aura) {
        aura.stacks.clear();
        MagicCircleSync.stop(aura.owner, aura.kind == AuraKind.MUTATION
                ? MagicCirclePayload.POISON_MUTATION : MagicCirclePayload.POISON_GALE);
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
            if (!entity.isAlive() || entity.isSpectator() || entity == owner || !hostile(owner, entity)) {
                continue;
            }
            double dist = distanceSq(center, entity.position());
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

    private static List<LivingEntity> alliesInRange(ServerLevel level, ServerPlayer owner,
                                                     Vec3 center, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            if (!entity.isAlive() || entity.isSpectator() || hostile(owner, entity)
                    || !within(center, entity.position(), radius)) {
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

    private static ColorParticleOption poisonParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, MagicElement.POISON.color());
    }

    private static void pulseParticles(ServerLevel level, Vec3 pos, int count) {
        level.sendParticles(poisonParticle(), pos.x, pos.y, pos.z,
                count, 0.35D, 0.5D, 0.35D, 0.04D);
    }

    private static void ringParticles(ServerLevel level, Vec3 center, double radius, int count) {
        double phase = level.getGameTime() * 0.03D;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count + phase;
            level.sendParticles(poisonParticle(),
                    center.x + Math.cos(angle) * radius, center.y + 0.18D,
                    center.z + Math.sin(angle) * radius,
                    2, 0.04D, 0.2D, 0.04D, 0.0D);
        }
    }

    /** 清除毒系免疫覆盖的负面状态 */
    private static void clearNegative(LivingEntity entity) {
        entity.removeEffect(MobEffects.POISON);
        entity.removeEffect(MobEffects.WITHER);
        entity.removeEffect(MobEffects.HUNGER);
        entity.removeEffect(MobEffects.NAUSEA);
        entity.removeEffect(MobEffects.BLINDNESS);
        entity.removeEffect(MobEffects.SLOWNESS);
        entity.removeEffect(MobEffects.WEAKNESS);
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

    private static final class Venom {
        final VenomKind kind;
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity target;
        final long endTick;
        final double damage;
        final int generation;
        /** 同一条感染链共用，防止来回反复传播 */
        final Set<UUID> infected;
        long nextPulseTick;
        long nextSpreadTick;

        Venom(VenomKind kind, ServerPlayer owner, ServerLevel level, LivingEntity target, long endTick,
              long nextPulseTick, double damage, int generation, Set<UUID> infected) {
            this.kind = kind;
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.nextSpreadTick = nextPulseTick + SPREAD_TICKS;
            this.damage = damage;
            this.generation = generation;
            this.infected = infected;
        }
    }

    private static final class Cloud {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 center;
        final double radius;
        final @Nullable FxBillboard display;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        /** true = 吐纳（锁定技能、结束进冷却）；false = 寄生毒残留 */
        final boolean timed;
        long nextPulseTick;

        Cloud(ServerPlayer owner, ServerLevel level, Vec3 center, double radius,
              @Nullable FxBillboard display, long endTick, long nextPulseTick,
              String skillId, int cooldownSeconds, boolean timed) {
            this.owner = owner;
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.display = display;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.timed = timed;
        }
    }

    private static final class Alchemy {
        final ServerPlayer owner;
        final ServerLevel level;
        final double radius;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        long nextPulseTick;

        Alchemy(ServerPlayer owner, ServerLevel level, double radius, long endTick, long nextPulseTick,
                String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.radius = radius;
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
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        /** 万噬之风：每个敌对已叠加的噬毒层数 */
        final Map<UUID, Integer> stacks = new HashMap<>();
        long nextPulseTick;

        Aura(ServerPlayer owner, ServerLevel level, AuraKind kind, float radius, long endTick,
             long nextPulseTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.kind = kind;
            this.radius = radius;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
}
