package com.cqgzs.cqlove.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.cqgzs.cqlove.magic.MagicElement;

/**
 * 客户端缓存：每只生物当前附着的元素（由服务端 ElementStatePayload 同步）。
 *
 * <p>Jade 的实体 tooltip provider 从这里读取并显示在生物名字下方。</p>
 */
public final class ElementClientState {

    private static final Map<UUID, Entry> STATES = new ConcurrentHashMap<>();

    private record Entry(List<MagicElement> elements, long expireAtMillis) {
    }

    /** 客户端收到服务端同步 */
    public static void accept(UUID entityId, List<Integer> ordinals, List<Integer> remainingTicks) {
        long now = System.currentTimeMillis();
        List<MagicElement> elements = new ArrayList<>();
        MagicElement[] values = MagicElement.values();
        for (int ordinal : ordinals) {
            if (ordinal >= 0 && ordinal < values.length) {
                elements.add(values[ordinal]);
            }
        }
        // 用剩余 tick 折算本地过期时间（20 tick = 1 秒）
        long maxTicks = remainingTicks.isEmpty() ? 0L : remainingTicks.stream().mapToLong(Integer::longValue).max().orElse(0L);
        STATES.put(entityId, new Entry(elements, now + maxTicks * 50L + 200L));
    }

    /** 读取某实体当前附着元素（本地已过期则返回空） */
    public static List<MagicElement> elementsOf(UUID entityId) {
        Entry entry = STATES.get(entityId);
        if (entry == null) {
            return List.of();
        }
        if (System.currentTimeMillis() > entry.expireAtMillis()) {
            STATES.remove(entityId);
            return List.of();
        }
        return entry.elements();
    }

    public static void clear() {
        STATES.clear();
    }
}
