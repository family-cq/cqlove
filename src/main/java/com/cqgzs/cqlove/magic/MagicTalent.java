package com.cqgzs.cqlove.magic;

/**
 * 天赋树（需求文档 5.1.2），每使用一本体质书获得 1 点天赋点。
 *
 * <p>{@link #gainPerPoint()} 是“每点增加的数值”，{@link #maxLevel()} 是等级上限。
 * 注意：速度与力量最终会写进原版属性，数值做了游戏性缩放（见 {@link #attributeScale()}）。</p>
 */
public enum MagicTalent {

    /** 速度：+0.1/点，20 级（原版移动速度基数只有 0.1，直接按 0.1 加会变成 20 倍，故按 1/20 缩放） */
    SPEED("速度", 0.1D, 20, 0.05D, 0.0D),
    /** 力量（攻击力）：+0.1/点，100 级 */
    STRENGTH("力量", 0.1D, 100, 1.0D, 0.0D),
    /** 蓝量：+1/点，100 级 */
    MANA("蓝量", 1.0D, 100, 1.0D, 0.0D),
    /** 血量：+1/点，100 级 */
    HEALTH("血量", 1.0D, 100, 1.0D, 0.0D),
    /** 魔力（魔法攻击力）：+0.1/点，100 级 */
    MAGIC_POWER("魔力", 0.1D, 100, 1.0D, 0.0D),
    /** 暴击率：初始 5%，+0.1%/点 → 内部按 0.1 计，100 级 = 再 +10% */
    CRIT("暴击", 0.1D, 100, 1.0D, 5.0D),
    /** 暴击伤害：初始 100%，+1%/点，100 级 */
    CRIT_DAMAGE("暴击伤害", 1.0D, 100, 1.0D, 100.0D),
    /** 魔暴击率：初始 5%，+0.1%/点 */
    MAGIC_CRIT("魔暴击", 0.1D, 100, 1.0D, 5.0D),
    /** 魔暴击伤害：初始 100%，+1%/点 */
    MAGIC_CRIT_DAMAGE("魔暴击伤害", 1.0D, 100, 1.0D, 100.0D),
    /** 元素精通：初始 100，+1/点，200 级（魔法伤害按 精通/100 缩放，100 = 不增不减） */
    ELEMENT_MASTERY("元素精通", 1.0D, 200, 1.0D, 100.0D);

    private final String display;
    private final double gainPerPoint;
    private final int maxLevel;
    private final double attributeScale;
    /** 玩家出生自带的初始属性值（暴击 5%、暴伤 100%、元素精通 100 等），非战斗天赋为 0 */
    private final double baseValue;

    MagicTalent(String display, double gainPerPoint, int maxLevel, double attributeScale, double baseValue) {
        this.display = display;
        this.gainPerPoint = gainPerPoint;
        this.maxLevel = maxLevel;
        this.attributeScale = attributeScale;
        this.baseValue = baseValue;
    }

    /** 玩家出生自带的初始属性值（百分比类单位同天赋：5 = 5%，100 = 100%） */
    public double baseValue() {
        return baseValue;
    }

    /** 初始属性 + 天赋加成 = 当前总值 */
    public double totalAt(int level) {
        return baseValue + valueAt(level);
    }

    public String display() {
        return display;
    }

    public double gainPerPoint() {
        return gainPerPoint;
    }

    public int maxLevel() {
        return maxLevel;
    }

    /**
     * 写入原版属性时的缩放系数。
     *
     * <p>速度在原版里基数只有 0.1，如果真按“每点 +0.1”会变成 20 倍速，所以按 1/20 缩放，
     * 满级 20 点正好 +0.1（即移速翻倍），既符合文档“总体增加 2 点”的意图又不至于失控。</p>
     */
    public double attributeScale() {
        return attributeScale;
    }

    /** 该天赋在指定等级下提供的数值 */
    public double valueAt(int level) {
        return gainPerPoint * Math.max(0, Math.min(maxLevel, level));
    }
}
