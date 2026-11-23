package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.cqgzs.cqlove.lan.PvpApi;

/**
 * <b>全模组统一的「玩家召唤物」登记表与对外 API</b>（亡灵系 / 召唤系共用）。
 *
 * <p>需求（2026-11-19）：召唤出来的生物要单独统计、区分不同玩家的归属；
 * PVP 关闭时，玩家不能攻击自己或别人召唤出来的生物，召唤物之间也不互相攻击；
 * 并且所有召唤物的选敌要有统一优先级。此前这两系各自维护 {@code SERVANTS} /
 * {@code MINIONS}，判定互不相认，因此抽到本类集中管理。</p>
 *
 * <p>登记表只存「实体 UUID -> (实体引用, 主人 UUID)」，不持有玩家对象，
 * 玩家下线后不会泄漏；{@link #prune()} 会清掉已移除的实体。</p>
 *
 * <p><b>接入方式</b>：各系生成召唤物后调 {@link #register(Entity, ServerPlayer)}，
 * 移除 / 死亡 / 清场时调 {@link #unregister(Entity)}；伤害拦截由
 * {@link #onIncomingDamage(LivingIncomingDamageEvent)} 在 {@code MagicEvents} 里统一调用；
 * 选敌直接调 {@link #preferredTarget(ServerPlayer, ServerLevel, Mob, double)}。</p>
 */
public final class SummonRegistry {

    private record Registration(Entity entity, UUID ownerId) {
    }

    /** 实体 UUID -> 登记信息 */
    private static final Map<UUID, Registration> REGISTRY = new HashMap<>();

    private SummonRegistry() {
    }

    // ==================================================================
    // 登记 / 查询（对外 API）
    // ==================================================================

    /** 登记一只召唤物（重复登记会覆盖归属），同时生成它的统一 10 项属性 */
    public static void register(Entity entity, ServerPlayer owner) {
        if (entity == null || owner == null) {
            return;
        }
        REGISTRY.put(entity.getUUID(), new Registration(entity, owner.getUUID()));
        // 统一属性 API：魔力=攻击力、暴击 5/100、魔暴 5/100、元素精通 100，
        // 原版速度/跳跃/力量/血量保持生物自身默认（只做快照，不改数值）。
        if (entity instanceof LivingEntity living) {
            SummonStats.generate(living);
        }
    }

    /** 注销一只召唤物（收回 / 死亡 / 清场时调用） */
    public static void unregister(@Nullable Entity entity) {
        if (entity != null) {
            REGISTRY.remove(entity.getUUID());
        }
    }

    /** 该实体是否是「任意玩家」召唤出来的生物 */
    public static boolean isSummoned(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        Registration registration = REGISTRY.get(entity.getUUID());
        return registration != null && !registration.entity().isRemoved();
    }

    /** 该召唤物的主人 UUID（非召唤物返回 null） */
    public static @Nullable UUID ownerIdOf(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        Registration registration = REGISTRY.get(entity.getUUID());
        return registration == null ? null : registration.ownerId();
    }

    /** 该召唤物是否属于这个玩家 */
    public static boolean isOwnedBy(@Nullable Entity entity, ServerPlayer owner) {
        return owner != null && Objects.equals(ownerIdOf(entity), owner.getUUID());
    }

    /** 在世界里找出登记的主人玩家（玩家已下线返回 null） */
    public static @Nullable ServerPlayer findOwner(UUID ownerId, ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (player.getUUID().equals(ownerId)) {
                return player;
            }
        }
        return null;
    }

    /** 列出某位玩家当前登记在册、仍然存活的召唤物（区分不同玩家） */
    public static List<LivingEntity> of(ServerPlayer owner) {
        List<LivingEntity> result = new ArrayList<>();
        for (Registration registration : REGISTRY.values()) {
            if (registration.ownerId().equals(owner.getUUID())
                    && registration.entity() instanceof LivingEntity living
                    && living.isAlive() && !living.isRemoved()) {
                result.add(living);
            }
        }
        return result;
    }

    /** 某位玩家当前存活的召唤物数量 */
    public static int countOf(ServerPlayer owner) {
        return of(owner).size();
    }

    /** 定期清掉已移除实体的登记（onServerTick 每 200 tick 调一次） */
    public static void prune() {
        REGISTRY.values().removeIf(registration -> registration.entity().isRemoved());
    }

    /** 服务器关闭 / 重开清场 */
    public static void clearAll() {
        REGISTRY.clear();
    }

    // ==================================================================
    // 伤害拦截（对外 API）
    // ==================================================================

    /**
     * 统一伤害拦截，返回 true 表示事件已取消、调用方直接 return。
     *
     * <ul>
     *   <li><b>PVP 关闭</b>：玩家不能攻击任何玩家召唤物（自己的或别人的都不行）；</li>
     *   <li>召唤物永远不能打自己的主人、也不能打自己；</li>
     *   <li><b>PVP 关闭</b>：召唤物不能攻击其他玩家、也不能攻击其他玩家的召唤物；</li>
     *   <li>同一玩家的召唤物之间永远不互殴（PVP 开启也不打）。</li>
     * </ul>
     *
     * <p>非召唤物的普通怪物仍然可以攻击召唤物，避免召唤物变成无敌靶子。</p>
     */
    public static boolean onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity attacker = event.getSource().getEntity();
        LivingEntity victim = event.getEntity();
        if (attacker == null) {
            return false;
        }

        // 1) 玩家 -> 召唤物：PVP 关闭一律禁止（含自己的召唤物）
        if (attacker instanceof ServerPlayer && isSummoned(victim) && !PvpApi.isPvpAllowed()) {
            event.setCanceled(true);
            return true;
        }

        // 2) 召唤物作为攻击者
        UUID ownerId = ownerIdOf(attacker);
        if (ownerId == null) {
            return false;
        }
        if (victim == attacker) {
            event.setCanceled(true);
            return true;
        }
        if (victim instanceof ServerPlayer victimPlayer) {
            ServerLevel level = victimPlayer.level() instanceof ServerLevel serverLevel
                    ? serverLevel : null;
            if (level != null) {
                ServerPlayer owner = findOwner(ownerId, level);
                if (owner == null || owner == victimPlayer || !PvpApi.canHurt(owner, victimPlayer)) {
                    event.setCanceled(true);
                    return true;
                }
            } else {
                event.setCanceled(true);
                return true;
            }
        }
        if (isSummoned(victim)) {
            // 同门永远不打；PVP 关闭时不同门也不打
            if (!PvpApi.isPvpAllowed() || Objects.equals(ownerId, ownerIdOf(victim))) {
                event.setCanceled(true);
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 统一选敌（对外 API）
    // ==================================================================

    /**
     * 召唤物的合法敌对目标：不是主人 / 不是任何玩家的召唤物 / 不是隐身或旁观者，
     * 并走 {@link CombatTracker#isEnemyTarget} 的 PVP 感知判定。
     */
    public static boolean isValidEnemy(ServerPlayer owner, LivingEntity candidate) {
        if (candidate == owner || !candidate.isAlive() || candidate.isSpectator()
                || candidate.isInvisible()) {
            return false;
        }
        if (isSummoned(candidate)) {
            return false;
        }
        return CombatTracker.isEnemyTarget(owner, candidate);
    }

    /**
     * 统一的选敌优先级：
     * <ol>
     *   <li><b>第一优先</b>：玩家最近攻击过的生物（{@link CombatTracker} 10 秒记忆）；</li>
     *   <li><b>第二优先</b>：玩家身边的敌对生物（以玩家为中心、{@code range} 格内）。</li>
     * </ol>
     *
     * <p>同优先级里按「离召唤物本体最近」挑，避免一群召唤物集体追最远的目标。
     * 返回 null 表示当前没有合法目标，调用方可以保留 vanilla 自己选的目标。</p>
     */
    public static @Nullable LivingEntity preferredTarget(ServerPlayer owner, ServerLevel level,
                                                          Mob self, double range) {
        return preferredTarget(owner, level, self.position(), range);
    }

    /**
     * 同上，但用任意坐标作为「距离」基准 —— 供没有真实实体的虚拟召唤物
     * （例如招魂术的纯特效鬼魂）复用同一套选敌优先级。
     */
    public static @Nullable LivingEntity preferredTarget(ServerPlayer owner, ServerLevel level,
                                                          net.minecraft.world.phys.Vec3 from, double range) {
        AABB area = owner.getBoundingBox().inflate(range);
        LivingEntity priority = null;
        double priorityDist = Double.MAX_VALUE;
        LivingEntity enemy = null;
        double enemyDist = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (!isValidEnemy(owner, candidate)) {
                continue;
            }
            double dist = candidate.distanceToSqr(from);
            if (CombatTracker.isRecentlyAttackedBy(owner, candidate)) {
                if (dist < priorityDist) {
                    priorityDist = dist;
                    priority = candidate;
                }
            } else if (dist < enemyDist) {
                enemyDist = dist;
                enemy = candidate;
            }
        }
        return priority != null ? priority : enemy;
    }
}
