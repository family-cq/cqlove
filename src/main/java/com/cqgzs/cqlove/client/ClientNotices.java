package com.cqgzs.cqlove.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

/**
 * 客户端提示队列：服务端下发的文字提示缓存于此，由 {@link NoticeHud} 画在屏幕中下位置。
 *
 * <p>不进聊天框。同名提示重复到达时会顶掉旧的一条，避免技能连按刷屏。
 * 最多保留 6 条，按到达顺序自下而上堆叠。</p>
 */
public final class ClientNotices {

    /** 每条提示展示时长：4 秒 */
    private static final long LIFE_MS = 4000L;
    /** 最多同时展示的条数 */
    private static final int MAX_LINES = 6;

    /** 包级可见，供同包的 NoticeHud 渲染 */
    record Entry(Component text, long expireAt) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private ClientNotices() {
    }

    /** 收到服务端提示，或客户端本地产生提示 */
    public static void push(Component text) {
        if (text == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 相同文本只保留最新一条（连按技能时不会叠出一串相同提示）
        ENTRIES.removeIf(e -> e.text().getString().equals(text.getString()));
        ENTRIES.add(new Entry(text, now + LIFE_MS));
        if (ENTRIES.size() > MAX_LINES) {
            ENTRIES.subList(0, ENTRIES.size() - MAX_LINES).clear();
        }
    }

    public static void push(String text) {
        push(Component.literal(text));
    }

    /** HUD 取当前可见条目（顺带惰性清理过期项） */
    public static List<Entry> visible() {
        long now = System.currentTimeMillis();
        ENTRIES.removeIf(e -> e.expireAt() <= now);
        return ENTRIES;
    }

    /** 剩余存活比例，1 = 刚到，0 = 即将消失（供淡出用） */
    public static float lifeRatio(Entry entry, long now) {
        long remain = entry.expireAt() - now;
        return Math.max(0.0F, Math.min(1.0F, (float) remain / (float) LIFE_MS));
    }
}
