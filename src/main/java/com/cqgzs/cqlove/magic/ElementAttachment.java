package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import com.cqgzs.cqlove.lan.PvpApi;
import com.cqgzs.cqlove.network.ElementStatePayload;

/**
 * <b>元素附着与反应系统</b>（服务端权威）。
 *
 * <p>12 种元素攻击命中后给生物附着元素，持续 7 秒（140 tick）；期间同种元素再次命中会
 * <b>刷新计时</b>。新元素附着时按附着先后依次尝试与已有元素反应：
 * 反应成功则两元素都消失并结算反应伤害；不反应则并存，继续和下一个元素比。</p>
 *
 * <p>反应伤害 = 基础伤害 × (施法者元素精通 / 100) × 反应倍率，再按魔暴击结算。
 * 附带效果：扩散（3×3×3 内生物附着元素）、晶核盾（抵挡一次魔法攻击）。</p>
 *
 * <h3>对外 API</h3>
 * <ul>
 *   <li>{@link #apply(ServerPlayer, LivingEntity, LivingEntity, MagicElement, double)} —— 魔法伤害命中时附着并反应；</li>
 *   <li>{@link #elementsOf(UUID)} —— 供 Jade / 客户端读取附着元素；</li>
 *   <li>{@link #tickWorld()} / {@link #clearAll()} —— 每 tick 清理与关服清场。</li>
 * </ul>
 */
public final class ElementAttachment {

    /** 元素附着持续 7 秒（140 tick），同种元素持续命中会刷新到此值 */
    public static final int ATTACH_TICKS = 140;

    /** 一条附着记录：元素 + 到期 tick（按附着先后放进 List，反应时按此顺序比对） */
    public static final class Attach {
        final MagicElement element;
        long expireTick;

        Attach(MagicElement element, long expireTick) {
            this.element = element;
            this.expireTick = expireTick;
        }

        public MagicElement element() {
            return element;
        }
    }

    /** 一只生物的附着状态 */
    private static final class State {
        LivingEntity entity;
        final List<Attach> list = new ArrayList<>();
        long lastSyncTick = -1L;

        State(LivingEntity entity) {
            this.entity = entity;
        }
    }

    /** 实体 UUID → 附着状态 */
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private ElementAttachment() {
    }

    // ==================================================================
    // 查询 API
    // ==================================================================

    /** 该实体当前附着的元素（只返回未过期的），供 Jade / 客户端读取 */
    public static List<MagicElement> elementsOf(UUID entityId) {
        State state = STATES.get(entityId);
        if (state == null) {
            return List.of();
        }
        long now = nowOf(state.entity);
        List<MagicElement> result = new ArrayList<>();
        for (Attach attach : state.list) {
            if (attach.expireTick > now) {
                result.add(attach.element);
            }
        }
        return result;
    }

    /** 该实体是否附着了指定元素 */
    public static boolean has(UUID entityId, MagicElement element) {
        return elementsOf(entityId).contains(element);
    }

    // ==================================================================
    // 核心：附着 + 反应
    // ==================================================================

    /**
     * 一次魔法伤害命中后调用：先附着元素（或刷新），再按附着顺序尝试反应。
     *
     * @param owner    施法者（玩家；用于 PVP 判定与盾牌归属）
     * @param attacker 实际造成伤害的实体（玩家本人或召唤物，决定属性来源）
     * @param target   被命中的生物
     * @param element  本次攻击的元素
     * @param baseDamage 本次攻击的基础伤害（反应在此之上加成）
     * @return 本次是否触发了反应（用于调用方决定是否额外显示）
     */
    public static boolean apply(ServerPlayer owner, LivingEntity attacker, LivingEntity target,
                                MagicElement element, double baseDamage) {
        if (target == null || !target.isAlive() || element == null) {
            return false;
        }
        ServerLevel level = target.level() instanceof ServerLevel sl ? sl : null;
        if (level == null) {
            return false;
        }
        long now = level.getGameTime();
        UUID id = target.getUUID();
        State state = STATES.computeIfAbsent(id, k -> new State(target));
        state.entity = target;

        // 1) 附着或刷新：同种元素已存在则重置到期 tick（持续攻击刷新 7 秒）
        Attach existing = null;
        for (Attach attach : state.list) {
            if (attach.element == element) {
                existing = attach;
                break;
            }
        }
        if (existing != null) {
            existing.expireTick = now + ATTACH_TICKS;
        } else {
            state.list.add(new Attach(element, now + ATTACH_TICKS));
        }

        // 2) 按附着顺序尝试反应：新元素优先和第一个元素反应，没反应再和第二个……
        boolean reacted = false;
        List<Attach> snapshot = new ArrayList<>(state.list);
        for (Attach other : snapshot) {
            if (other.element == element || other.expireTick <= now) {
                continue;
            }
            ElementReaction.Reaction reaction = ElementReaction.get(element, other.element);
            if (reaction == null) {
                continue; // 不反应，继续比下一个
            }
            // 反应成功：两个元素都移除
            state.list.removeIf(a -> a.element == element || a.element == other.element);
            triggerReaction(owner, attacker, target, reaction, baseDamage, level, now);
            reacted = true;
            break; // 一次攻击只触发一次反应；剩余元素保留等待下次
        }

        syncToClients(state, now);
        return reacted;
    }

    /** 结算一次反应：伤害 + 扩散 + 盾牌 + 飘字 */
    private static void triggerReaction(ServerPlayer owner, LivingEntity attacker, LivingEntity target,
                                        ElementReaction.Reaction reaction, double baseDamage,
                                        ServerLevel level, long now) {
        // ---- 属性来源：玩家用 PlayerMagicData，召唤物用 SummonStats ----
        double mastery;
        double magicCritChance;
        double magicCritDamage;
        boolean fireOrThunderGod = false;
        if (attacker instanceof ServerPlayer player) {
            PlayerMagicData data = MagicData.get(player);
            mastery = data.elementMastery();
            magicCritChance = data.magicCritChance() / 100.0D;
            magicCritDamage = data.magicCritDamage() / 100.0D;
            fireOrThunderGod = data.fireGodActive() || data.thunderGodActive();
        } else {
            SummonStats.Stats stats = SummonStats.get(attacker);
            mastery = stats.elementMastery();
            magicCritChance = stats.magicCritChance() / 100.0D;
            magicCritDamage = stats.magicCritDamage() / 100.0D;
        }

        // 反应伤害 = 基础 × (精通/100) × 倍率，再算魔暴击
        double damage = baseDamage * (mastery / 100.0D) * reaction.multiplier();
        double critDamage = fireOrThunderGod ? magicCritDamage + 1.0D : magicCritDamage;
        boolean crit = attacker.getRandom().nextDouble() < magicCritChance;
        double finalDamage = crit ? damage * (1.0D + critDamage) : damage;

        // PVP 判定
        if (target instanceof Player victim && !PvpApi.canHurt(owner, victim)) {
            return;
        }
        // 用原版 MAGIC 源：反应伤害不再触发元素附着，避免连锁反应
        DamageSource source = level.damageSources().magic();
        boolean hurt = target.hurtServer(level, source, (float) finalDamage);
        if (hurt) {
            // 敌人头上显示「xxx反应 伤害数值」
            DamageNumbers.spawnNamed(level,
                    target.position().add(0.0D, target.getBbHeight() + 0.25D, 0.0D),
                    reaction.name(), finalDamage, 0xFFFF66FF);
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFFCC66FF),
                    target.getX(), target.getY() + target.getBbHeight() * 0.6D, target.getZ(),
                    18, 0.4D, 0.5D, 0.4D, 0.08D);
        }

        // 扩散：3×3×3 内生物附着元素（不连锁反应，只附着）
        if (reaction.spread() != null) {
            for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(target.position(), target.position()).inflate(1.5D))) {
                if (near == target || !near.isAlive()) {
                    continue;
                }
                UUID nearId = near.getUUID();
                State ns = STATES.computeIfAbsent(nearId, k -> new State(near));
                ns.entity = near;
                Attach found = null;
                for (Attach a : ns.list) {
                    if (a.element == reaction.spread()) {
                        found = a;
                        break;
                    }
                }
                if (found != null) {
                    found.expireTick = now + ATTACH_TICKS;
                } else {
                    ns.list.add(new Attach(reaction.spread(), now + ATTACH_TICKS));
                }
                syncToClients(ns, now);
            }
        }

        // 晶核盾：给施法者生成一个抵挡一次魔法攻击的盾
        if (reaction.shield() != null && owner.isAlive()) {
            ElementShield.grant(owner, reaction.shield());
        }
    }

    // ==================================================================
    // 每 tick：清理过期 + 低频同步
    // ==================================================================

    public static void tickWorld() {
        Iterator<Map.Entry<UUID, State>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, State> entry = iterator.next();
            State state = entry.getValue();
            long now = nowOf(state.entity);
            // 生物死亡 / 移除：无条件清掉它的附着
            if (!state.entity.isAlive() || state.entity.isRemoved()) {
                iterator.remove();
                continue;
            }
            boolean changed = state.list.removeIf(a -> a.expireTick <= now);
            if (changed || now - state.lastSyncTick >= 20L) {
                syncToClients(state, now);
            }
        }
    }

    private static void syncToClients(State state, long now) {
        state.lastSyncTick = now;
        List<Integer> elements = new ArrayList<>();
        List<Integer> ticks = new ArrayList<>();
        for (Attach attach : state.list) {
            if (attach.expireTick > now) {
                elements.add(attach.element.ordinal());
                ticks.add((int) Math.max(0L, attach.expireTick - now));
            }
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(state.entity,
                new ElementStatePayload(state.entity.getUUID(), elements, ticks));
    }

    private static long nowOf(LivingEntity entity) {
        return entity.level() != null ? entity.level().getGameTime() : 0L;
    }

    /** 关服 / 重开清场 */
    public static void clearAll() {
        STATES.clear();
        ElementShield.clearAll();
    }

    /** 生物死亡时清掉它的附着 */
    public static void onEntityDeath(LivingEntity entity) {
        STATES.remove(entity.getUUID());
    }
}
