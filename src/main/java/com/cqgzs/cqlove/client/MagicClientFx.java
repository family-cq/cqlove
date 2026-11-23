package com.cqgzs.cqlove.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.network.MagicFxPayload;

/**
 * 客户端“跟随特效”状态缓存：每个玩家的翅膀 / 法阵开关位（服务端 {@link MagicFxPayload} 推来），
 * 以及超阶技能的“脚下圆环”倒计时（{@code CastRingPayload} 推来）。
 *
 * <p>超阶圆环的时长由<b>服务端</b>决定：技能持续期间一直刷新剩余 tick，
 * 技能正式进入冷却时服务端发一个清除包。客户端本地也做倒计时（防止偶发丢包时残留），
 * 但每次补发都会把结束时刻重新对齐服务端，不会自己提前把圆环抹掉。</p>
 *
 * <p>这些数据画在<b>所有玩家</b>身上（不只自己），所以按 UUID 缓存；
 * 渲染时通过 NeoForge 的渲染状态扩展（{@link #FLAGS_KEY} / {@link #CAST_RING_KEY}）
 * 传进 {@code AvatarRenderState}，由 {@link PlayerFxLayer} 读取。</p>
 */
public final class MagicClientFx {

    /** 已学风之翼：背后六翼 */
    public static final int WIND_WINGS = 1;
    /** 火神焚天 buff：脚下火环 */
    public static final int FIRE_GOD = 2;
    /** 雷神降临 buff：脚下雷环（与火环同时存在时优先显示） */
    public static final int THUNDER_GOD = 4;

    /** 渲染状态扩展键：把 flags 塞进 AvatarRenderState */
    public static final ContextKey<Integer> FLAGS_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_fx_flags"));

    /** 渲染状态扩展键：把“超阶圆环”的剩余时间塞进 AvatarRenderState（没有则移除该键） */
    public static final ContextKey<CastRingView> CAST_RING_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_cast_ring"));

    /** 渲染状态扩展键：把该玩家当前所有心灵系圆圈塞进 AvatarRenderState */
    public static final ContextKey<List<CircleView>> CIRCLES_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_circles"));

    private static final Map<UUID, Integer> FLAGS = new ConcurrentHashMap<>();
    /** 玩家 -> 超阶圆环（系列序号 + 总时长 + 到期时的客户端游戏刻） */
    private static final Map<UUID, CastRing> CAST_RINGS = new ConcurrentHashMap<>();
    /** 玩家 -> (圆圈样式 -> 状态)，支持一个玩家同时叠多个圈 */
    private static final Map<UUID, Map<Integer, CircleState>> CIRCLES = new ConcurrentHashMap<>();

    /** 可变：服务端补发时只刷新 endGameTime，保留首次的总时长，避免放大动画反复重播 */
    private static final class CastRing {
        final int elementOrdinal;
        final int durationTicks;
        long endGameTime;

        CastRing(int elementOrdinal, int durationTicks, long endGameTime) {
            this.elementOrdinal = elementOrdinal;
            this.durationTicks = durationTicks;
            this.endGameTime = endGameTime;
        }
    }

    /**
     * 渲染用的圆环视图。
     *
     * @param elementOrdinal 系列序号（对应 {@code MagicElement.values()}）
     * @param remainingTicks 服务端同步后还剩多少 tick
     * @param durationTicks  首次收到时的总时长（用于放大 / 淡出节奏，补发不会重置）
     */
    public record CastRingView(int elementOrdinal, int remainingTicks, int durationTicks) {
    }

    /** 单个心灵系圆圈的渲染视图 */
    public record CircleView(int variant, int remainingTicks, int durationTicks, float radius) {
    }

    /** 可变：服务端低频补发时只刷新到期时刻，保留首次总时长，避免展开动画反复重播 */
    private static final class CircleState {
        final int durationTicks;
        final float radius;
        long endGameTime;

        CircleState(int durationTicks, float radius, long endGameTime) {
            this.durationTicks = durationTicks;
            this.radius = radius;
            this.endGameTime = endGameTime;
        }
    }

    private MagicClientFx() {
    }

    /** 收到服务端广播的跟随特效开关位 */
    public static void accept(MagicFxPayload payload) {
        if (payload.flags() == 0) {
            FLAGS.remove(payload.player());
        } else {
            FLAGS.put(payload.player(), payload.flags());
        }
    }

    /**
     * 收到服务端广播的超阶圆环状态。
     *
     * @param elementOrdinal 系列序号，&lt; 0 表示清除
     * @param remainingTicks 剩余 tick；&lt;= 0 表示清除
     */
    public static void acceptCastRing(UUID player, int elementOrdinal, int remainingTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (elementOrdinal < 0 || remainingTicks <= 0) {
            CAST_RINGS.remove(player);
            return;
        }
        long end = minecraft.level.getGameTime() + remainingTicks;
        CastRing existing = CAST_RINGS.get(player);
        if (existing != null && existing.elementOrdinal == elementOrdinal) {
            // 定期补发：只把倒计时对齐服务端，不重播“从小到大展开”的动画
            existing.endGameTime = end;
            return;
        }
        CAST_RINGS.put(player, new CastRing(elementOrdinal, remainingTicks, end));
    }

    /**
     * 收到服务端广播的心灵系圆圈状态。
     *
     * @param variant        圆圈样式；{@code MagicCirclePayload.CLEAR_ALL} 表示清掉该玩家全部圆圈
     * @param remainingTicks 剩余 tick；&lt;= 0 表示清除该样式
     * @param radius         视觉圆环半径（格）
     */
    public static void acceptCircle(UUID player, int variant, int remainingTicks, float radius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (variant == com.cqgzs.cqlove.network.MagicCirclePayload.CLEAR_ALL) {
            CIRCLES.remove(player);
            return;
        }
        if (variant < 0 || variant >= com.cqgzs.cqlove.network.MagicCirclePayload.COUNT) {
            return;
        }
        if (remainingTicks <= 0) {
            Map<Integer, CircleState> map = CIRCLES.get(player);
            if (map != null) {
                map.remove(variant);
                if (map.isEmpty()) {
                    CIRCLES.remove(player);
                }
            }
            return;
        }
        long end = minecraft.level.getGameTime() + remainingTicks;
        Map<Integer, CircleState> map = CIRCLES.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
        CircleState existing = map.get(variant);
        if (existing != null) {
            // 低频补发：只对齐倒计时，不重播展开动画
            existing.endGameTime = end;
            return;
        }
        map.put(variant, new CircleState(remainingTicks, radius, end));
    }

    /** 某玩家当前该画的所有心灵系圆圈；本地倒计时到期自动移除 */
    public static List<CircleView> circlesOf(UUID playerId) {
        Map<Integer, CircleState> map = CIRCLES.get(playerId);
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            CIRCLES.remove(playerId);
            return List.of();
        }
        long now = minecraft.level.getGameTime();
        List<CircleView> result = new ArrayList<>(map.size());
        for (var entry : map.entrySet()) {
            CircleState state = entry.getValue();
            int remaining = (int) (state.endGameTime - now);
            if (remaining <= 0) {
                continue;
            }
            result.add(new CircleView(entry.getKey(), remaining, state.durationTicks, state.radius));
        }
        // 惰性清理过期项（不在遍历时改 map）
        if (result.size() < map.size()) {
            map.entrySet().removeIf(e -> e.getValue().endGameTime <= now);
            if (map.isEmpty()) {
                CIRCLES.remove(playerId);
            }
        }
        return result;
    }

    /** 某玩家当前的特效开关位（没有记录 = 0） */
    public static int flagsOf(UUID playerId) {
        return FLAGS.getOrDefault(playerId, 0);
    }

    /** 某玩家当前是否该画超阶圆环；本地倒计时到期则顺手清掉并返回 null */
    public static CastRingView castRingOf(UUID playerId) {
        CastRing ring = CAST_RINGS.get(playerId);
        if (ring == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            CAST_RINGS.remove(playerId);
            return null;
        }
        int remaining = (int) (ring.endGameTime - minecraft.level.getGameTime());
        if (remaining <= 0) {
            CAST_RINGS.remove(playerId);
            return null;
        }
        return new CastRingView(ring.elementOrdinal, remaining, ring.durationTicks);
    }

    /** 退出世界时清空 */
    public static void clear() {
        FLAGS.clear();
        CAST_RINGS.clear();
        CIRCLES.clear();
    }
}
