package com.cqgzs.cqlove.magic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import com.cqgzs.cqlove.lan.PvpApi;

/**
 * 玩家最近攻击过的生物记录（风系/水系超阶技能的自动追踪目标判定用）。
 *
 * <p>需求：超阶技能自动追击「敌对生物」和「玩家攻击过的生物」——
 * 敌对生物用原版 {@link Enemy} 接口判断（僵尸/骷髅/苦力怕等），
 * 另一半（玩家用剑砍过的牛、被技能命中的玩家等）由本类记录：
 * 玩家对生物造成伤害时记下时间，10 秒内都算合法目标。</p>
 */
public final class CombatTracker {

    /** 攻击记录的有效期（tick）：10 秒内打过都算 */
    private static final long MEMORY_TICKS = 20L * 10L;

    /** 攻击者 UUID -> (目标 UUID -> 记录时的游戏 tick) */
    private static final Map<UUID, Map<UUID, Long>> ATTACKED = new HashMap<>();

    private CombatTracker() {
    }

    /** 记录一次“玩家对生物的攻击”（近战 / 弓箭 / 技能魔法伤害都会走到） */
    public static void record(ServerPlayer attacker, LivingEntity target) {
        if (target == attacker) {
            return;
        }
        ATTACKED.computeIfAbsent(attacker.getUUID(), key -> new HashMap<>())
                .put(target.getUUID(), attacker.level().getGameTime());
    }

    /** 该生物是否是玩家在有效期内攻击过的目标 */
    public static boolean isRecentlyAttackedBy(ServerPlayer attacker, LivingEntity target) {
        Map<UUID, Long> hits = ATTACKED.get(attacker.getUUID());
        if (hits == null) {
            return false;
        }
        Long tick = hits.get(target.getUUID());
        if (tick == null) {
            return false;
        }
        return attacker.level().getGameTime() - tick <= MEMORY_TICKS;
    }

    /**
     * 超阶技能的自动追踪判定：敌对生物 或 玩家近期攻击过的生物。
     *
     * <p>风系泯风千刃与水之猎刃共用，保证两系行为一致。</p>
     */
    public static boolean isHuntable(ServerPlayer attacker, LivingEntity candidate) {
        if (candidate == attacker || !candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }
        if (candidate instanceof Enemy) {
            return true;
        }
        return isRecentlyAttackedBy(attacker, candidate);
    }

    // ==================================================================
    // 全系统一目标判定（PVP 感知）
    // ==================================================================

    /**
     * 全模组统一的「敌对目标」判定：所有伤害类技能都用它选目标。
     *
     * <ul>
     *   <li>自己 / 死亡 / 旁观者：永远不是目标；</li>
     *   <li><b>其他玩家</b>：仅当 PVP 开启时算敌对目标，PVP 关闭时不算
     *       （配合 {@code MagicDamage.magicDamage} 与 {@link PvpApi} 的伤害事件兜底，
     *       保证 PVP 关闭时绝不误伤其他玩家）；</li>
     *   <li>生物：原版敌对生物（{@link Enemy}）或玩家近期攻击过的生物。</li>
     * </ul>
     */
    public static boolean isEnemyTarget(ServerPlayer owner, LivingEntity candidate) {
        if (candidate == owner || !candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }
        if (candidate instanceof Player) {
            return PvpApi.isPvpAllowed();
        }
        return candidate instanceof Enemy || isRecentlyAttackedBy(owner, candidate);
    }

    /**
     * 全模组统一的「友方目标」判定：所有治疗 / 增益技能都用它选目标。
     *
     * <p>其他玩家永远算友方（PVP 开关不影响给玩家加血 / 上增益），
     * 施法者自己也算友方；敌对生物与玩家近期攻击过的生物不算。</p>
     */
    public static boolean isAllyTarget(ServerPlayer owner, LivingEntity candidate) {
        if (!candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }
        if (candidate instanceof Player) {
            return true;
        }
        return !(candidate instanceof Enemy) && !isRecentlyAttackedBy(owner, candidate);
    }

    /** 过期清理（onServerTick 每 200 tick 一次，防止 map 无限增长） */
    public static void prune(long now) {
        Iterator<Map<UUID, Long>> outer = ATTACKED.values().iterator();
        while (outer.hasNext()) {
            Map<UUID, Long> hits = outer.next();
            hits.values().removeIf(tick -> now - tick > MEMORY_TICKS);
            if (hits.isEmpty()) {
                outer.remove();
            }
        }
    }

    /** 服务器关闭 / 测试重置时清空 */
    public static void clear() {
        ATTACKED.clear();
    }
}
