package com.cqgzs.cqlove.magic;

/**
 * 技能阶级（需求文档 5.1.1）与统一数值规则（需求 5.1.3）。
 *
 * <p>所有技能共用这里的施法范围 / 耗蓝 / 冷却 / 施法时间计算，技能本身不写死数值。</p>
 */
public enum SkillTier {

    /** 初阶技能 */
    NOVICE("初阶"),
    /** 中阶技能 */
    INTERMEDIATE("中阶"),
    /** 高阶技能 */
    ADVANCED("高阶"),
    /** 超阶（超级）技能 */
    SUPER("超阶");

    /**
     * 冷却表：行 = 玩家阶级，列 = 技能阶（需求 5.1.3 e）。
     * <pre>
     * 初阶玩家：初阶 15 秒
     * 中阶玩家：初阶 10 秒，中阶 20 秒
     * 高阶玩家：初阶 5 秒，中阶 15 秒，高阶 40 秒
     * 超阶玩家：初阶 1 秒，中阶 10 秒，高阶 30 秒，超阶 60 秒
     * </pre>
     */
    private static final int[][] COOLDOWN_SECONDS = {
            {15},
            {10, 20},
            {5, 15, 40},
            {1, 10, 30, 60}
    };

    /**
     * 耗蓝表（需求 5.1.3 c）：初阶 1/3/5，中阶 8/10/12，高阶 20，超阶 30。
     * 数组长度不足 3 的阶级（高/超阶）所有技能都用最后一个值。
     */
    private static final int[][] MANA_COST = {
            {1, 3, 5},
            {8, 10, 12},
            {20},
            {30}
    };

    private final String display;

    SkillTier(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /**
     * 施法时间（秒，需求 5.1.3 a）。
     *
     * <p>规律：玩家阶级比技能阶高一级就快 0.5 秒，最快 1.0 秒，最低 2.5 秒。</p>
     */
    public double castSeconds(MagicRank rank) {
        double seconds = 2.5D - 0.5D * (rank.ordinal() - ordinal());
        return Math.max(1.0D, Math.min(2.5D, seconds));
    }

    /** 该技能在指定玩家阶级下的冷却秒数 */
    public int cooldownSeconds(MagicRank rank) {
        int[] row = COOLDOWN_SECONDS[Math.min(rank.ordinal(), COOLDOWN_SECONDS.length - 1)];
        return row[Math.min(ordinal(), row.length - 1)];
    }

    /**
     * 该技能的耗蓝量。
     *
     * @param indexInTier 同阶内的第几个技能（1 起）。只有一个技能的系列一律按第 1 个算。
     */
    public int manaCost(int indexInTier) {
        int[] row = MANA_COST[ordinal()];
        return row[Math.min(Math.max(indexInTier, 1), row.length) - 1];
    }

    public static SkillTier byOrdinal(int ordinal) {
        SkillTier[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, ordinal))];
    }
}
