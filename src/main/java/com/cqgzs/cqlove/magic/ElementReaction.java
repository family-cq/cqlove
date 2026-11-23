package com.cqgzs.cqlove.magic;

import org.jspecify.annotations.Nullable;

/**
 * 元素反应表（66 种组合，反应相互对称：雷+火 == 火+雷）。
 *
 * <p>反应伤害公式：{@code 基础伤害 × (元素精通/100) × 倍率}，之后再按施法者的魔暴击结算。</p>
 *
 * <p>三种附带效果：</p>
 * <ul>
 *   <li>{@code spread}：扩散 —— 3×3×3 内的生物也附着该元素；</li>
 *   <li>{@code shield}：晶核 —— 给施法者生成一个对应元素的盾，抵挡一次魔法攻击；</li>
 *   <li>都不填则纯伤害反应。返回 null 表示"不反应"。</li>
 * </ul>
 */
public final class ElementReaction {

    /** 一条反应定义 */
    public record Reaction(String name, double multiplier,
                           @Nullable MagicElement spread, @Nullable MagicElement shield) {
    }

    private static final java.util.Map<Long, Reaction> TABLE = new java.util.HashMap<>();

    private static long key(MagicElement a, MagicElement b) {
        int x = a.ordinal();
        int y = b.ordinal();
        int lo = Math.min(x, y);
        int hi = Math.max(x, y);
        return ((long) lo << 16) | (hi & 0xFFFFL);
    }

    private static void put(MagicElement a, MagicElement b, String name, double multiplier,
                            @Nullable MagicElement spread, @Nullable MagicElement shield) {
        TABLE.put(key(a, b), new Reaction(name, multiplier, spread, shield));
    }

    /** 纯伤害反应 */
    private static void put(MagicElement a, MagicElement b, String name, double multiplier) {
        put(a, b, name, multiplier, null, null);
    }

    /** 不反应（显式登记，便于日志核对完整性） */
    private static void none(MagicElement a, MagicElement b) {
        // 不写进表：get() 返回 null 即不反应
    }

    // ===== 雷 =====
    static {
        put(MagicElement.THUNDER, MagicElement.FIRE, "超载", 2.0D);
        put(MagicElement.THUNDER, MagicElement.WIND, "扩散雷", 1.0D, MagicElement.THUNDER, null);
        put(MagicElement.THUNDER, MagicElement.WATER, "感电", 1.5D);
        put(MagicElement.THUNDER, MagicElement.LIGHT, "极限", 2.0D);
        put(MagicElement.THUNDER, MagicElement.EARTH, "晶核反应", 1.0D, null, MagicElement.THUNDER);
        put(MagicElement.THUNDER, MagicElement.ICE, "星烁", 2.0D);
        none(MagicElement.THUNDER, MagicElement.POISON);
        put(MagicElement.THUNDER, MagicElement.PLANT, "激化", 1.5D);
        put(MagicElement.THUNDER, MagicElement.SPACE, "镜面反应", 1.5D);
        put(MagicElement.THUNDER, MagicElement.SHADOW, "魔雷", 2.0D);
        none(MagicElement.THUNDER, MagicElement.CURSE);

        // ===== 火 =====
        put(MagicElement.FIRE, MagicElement.WIND, "扩散火", 1.0D, MagicElement.FIRE, null);
        put(MagicElement.FIRE, MagicElement.WATER, "蒸发", 1.5D);
        none(MagicElement.FIRE, MagicElement.LIGHT);
        put(MagicElement.FIRE, MagicElement.EARTH, "晶核反应", 1.0D, null, MagicElement.FIRE);
        put(MagicElement.FIRE, MagicElement.ICE, "融化", 1.5D);
        put(MagicElement.FIRE, MagicElement.POISON, "火毒", 2.0D);
        put(MagicElement.FIRE, MagicElement.PLANT, "燃烧", 1.5D);
        put(MagicElement.FIRE, MagicElement.SPACE, "压缩", 2.0D);
        put(MagicElement.FIRE, MagicElement.SHADOW, "狱火", 2.0D);
        none(MagicElement.FIRE, MagicElement.CURSE);

        // ===== 风 =====
        put(MagicElement.WIND, MagicElement.WATER, "扩散水", 1.0D, MagicElement.WATER, null);
        put(MagicElement.WIND, MagicElement.LIGHT, "扩散光", 1.0D, MagicElement.LIGHT, null);
        put(MagicElement.WIND, MagicElement.EARTH, "晶核反应", 1.0D, null, MagicElement.EARTH);
        put(MagicElement.WIND, MagicElement.ICE, "扩散冰", 1.0D, MagicElement.ICE, null);
        put(MagicElement.WIND, MagicElement.POISON, "扩散毒", 1.0D, MagicElement.POISON, null);
        put(MagicElement.WIND, MagicElement.PLANT, "扩散草", 1.0D, MagicElement.PLANT, null);
        none(MagicElement.WIND, MagicElement.SPACE);
        put(MagicElement.WIND, MagicElement.SHADOW, "扩散黑暗", 1.0D, MagicElement.SHADOW, null);
        put(MagicElement.WIND, MagicElement.CURSE, "扩散诅咒", 1.0D, MagicElement.CURSE, null);

        // ===== 水 =====
        put(MagicElement.WATER, MagicElement.LIGHT, "丁达", 1.5D);
        put(MagicElement.WATER, MagicElement.EARTH, "晶核反应", 1.0D, null, MagicElement.WATER);
        put(MagicElement.WATER, MagicElement.ICE, "冰冻", 1.5D);
        put(MagicElement.WATER, MagicElement.POISON, "水毒", 2.0D);
        put(MagicElement.WATER, MagicElement.PLANT, "种子", 1.5D);
        put(MagicElement.WATER, MagicElement.SPACE, "压迫", 2.0D);
        put(MagicElement.WATER, MagicElement.SHADOW, "地牢", 2.0D);
        put(MagicElement.WATER, MagicElement.CURSE, "水魔", 2.0D);

        // ===== 光 =====
        put(MagicElement.LIGHT, MagicElement.EARTH, "净化", 1.5D);
        put(MagicElement.LIGHT, MagicElement.ICE, "反光", 1.5D);
        none(MagicElement.LIGHT, MagicElement.POISON);
        put(MagicElement.LIGHT, MagicElement.PLANT, "光合", 1.5D);
        put(MagicElement.LIGHT, MagicElement.SPACE, "永恒", 2.0D);
        put(MagicElement.LIGHT, MagicElement.SHADOW, "极致", 2.0D);
        put(MagicElement.LIGHT, MagicElement.CURSE, "灼烧", 1.5D);

        // ===== 土 =====
        put(MagicElement.EARTH, MagicElement.ICE, "晶核反应", 1.0D, null, MagicElement.ICE);
        put(MagicElement.EARTH, MagicElement.POISON, "晶核反应", 1.0D, null, MagicElement.POISON);
        put(MagicElement.EARTH, MagicElement.PLANT, "晶核反应", 1.0D, null, MagicElement.PLANT);
        put(MagicElement.EARTH, MagicElement.SPACE, "结界", 2.0D);
        // 需求原文即「土+黑暗 → 水盾」，照抄
        put(MagicElement.EARTH, MagicElement.SHADOW, "晶核反应", 1.0D, null, MagicElement.WATER);
        put(MagicElement.EARTH, MagicElement.CURSE, "限制", 2.0D);

        // ===== 冰 =====
        put(MagicElement.ICE, MagicElement.POISON, "蛤蟆", 2.0D);
        none(MagicElement.ICE, MagicElement.PLANT);
        put(MagicElement.ICE, MagicElement.SPACE, "镜面反应", 2.0D);
        put(MagicElement.ICE, MagicElement.SHADOW, "冰牢", 1.5D);
        put(MagicElement.ICE, MagicElement.CURSE, "凝固", 1.5D);

        // ===== 毒 =====
        put(MagicElement.POISON, MagicElement.PLANT, "剧毒", 1.5D);
        put(MagicElement.POISON, MagicElement.SPACE, "梦幻", 2.0D);
        put(MagicElement.POISON, MagicElement.SHADOW, "迷幻", 1.5D);
        put(MagicElement.POISON, MagicElement.CURSE, "致幻", 2.0D);

        // ===== 草 =====
        none(MagicElement.PLANT, MagicElement.SPACE);
        put(MagicElement.PLANT, MagicElement.SHADOW, "邪魂", 2.0D);
        put(MagicElement.PLANT, MagicElement.CURSE, "擦边", 1.5D);

        // ===== 空间 =====
        put(MagicElement.SPACE, MagicElement.SHADOW, "无边", 2.0D);
        none(MagicElement.SPACE, MagicElement.CURSE);

        // ===== 黑暗 =====
        put(MagicElement.SHADOW, MagicElement.CURSE, "恐惧", 2.0D);
    }

    private ElementReaction() {
    }

    /** 查反应（对称）。返回 null 表示不反应。 */
    public static @Nullable Reaction get(MagicElement a, MagicElement b) {
        if (a == null || b == null || a == b) {
            return null;
        }
        return TABLE.get(key(a, b));
    }

    /** 反应表条目数（供自检） */
    public static int size() {
        return TABLE.size();
    }
}
