package com.cqgzs.cqlove.magic;

/**
 * 魔法师阶级（需求文档 5.1.3）。
 *
 * <p>阶级决定：能觉醒几个魔法系列、能学最高几阶技能、施法时间、蓝量恢复速度、技能冷却。</p>
 */
public enum MagicRank {

    /** 初阶：只能觉醒 1 个系列，只能学初阶技能 */
    NOVICE("初阶", 10),
    /** 中阶：觉醒 2 个系列，可学初阶+中阶 */
    INTERMEDIATE("中阶", 9),
    /** 高阶：觉醒 3 个系列，可学初/中/高阶 */
    ADVANCED("高阶", 7),
    /** 超阶：觉醒 4 个系列（上限），可学全部四阶 */
    SUPER("超阶", 4);

    /** 界面/聊天栏显示名 */
    private final String display;
    /** 每恢复 1 点蓝量所需秒数（需求 5.1.3 d） */
    private final int manaRegenSeconds;

    MagicRank(String display, int manaRegenSeconds) {
        this.display = display;
        this.manaRegenSeconds = manaRegenSeconds;
    }

    public String display() {
        return display;
    }

    public int manaRegenSeconds() {
        return manaRegenSeconds;
    }

    /** 最多可觉醒的魔法系列数量：初阶 1 → 超阶 4（需求 5.3，同时是玩家的硬上限 4） */
    public int maxSeries() {
        return ordinal() + 1;
    }

    /** 该阶级能学习的最高技能阶（等于自身阶） */
    public int maxLearnableTier() {
        return ordinal();
    }

    /** 是否还能升阶 */
    public boolean canRankUp() {
        return this != SUPER;
    }

    /**
     * 施法范围（格）：由<b>玩家阶级</b>决定，对该玩家可用的所有技能生效
     * （需求 2026-11-13：初阶 10 / 中阶 15 / 高阶 20 / 超阶 30，包括低阶技能）。
     *
     * <p><b>这是全工程施法范围的唯一数据源</b>：技能实现只能通过 {@code SkillContext.range()}
     * / {@code targetPos()} / {@code travelTicks()} / {@code clampDistance()} 取用，
     * 不允许再写死「16 格 / 20 格 / 24 tick / 9 格推进」之类上限，否则升阶后范围不会变大。
     * 技能自身的固有尺寸（爆炸半径、墙体大小、领域半径等）不在此列。</p>
     */
    public double castRange() {
        return switch (this) {
            case NOVICE -> 10.0D;
            case INTERMEDIATE -> 15.0D;
            case ADVANCED -> 20.0D;
            case SUPER -> 30.0D;
        };
    }

    public MagicRank next() {
        MagicRank[] all = values();
        return this == SUPER ? SUPER : all[ordinal() + 1];
    }

    /** 按序号取，越界自动夹取 */
    public static MagicRank byOrdinal(int ordinal) {
        MagicRank[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, ordinal))];
    }
}
