package com.cqgzs.cqlove.magic;

/**
 * 技能定义（需求文档 5.1.1 的“技能线”基本单位）。
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code id}       全局唯一 id，格式 “系列_阶_序号”，例如 thunder_0_1；存档与网络都靠它</li>
 *   <li>{@code tierName} 同阶技能组的名字，例如“雷印”“霹雳”</li>
 *   <li>{@code name}     技能名，例如“蟒痕”</li>
 *   <li>{@code action}   实际释放逻辑（未实现的技能为空实现，会提示玩家）</li>
 * </ul>
 *
 * <p>耗蓝、冷却、施法范围、施法时间统一由 {@link SkillTier} + 玩家阶级计算，技能本身不写死。</p>
 */
public record SkillDefinition(
        String id,
        MagicElement element,
        SkillTier tier,
        int indexInTier,
        String tierName,
        String name,
        String description,
        SkillAction action) {

    /** 该技能的耗蓝（按阶 + 同阶序号，见需求 5.1.3 c） */
    public int manaCost() {
        return tier.manaCost(indexInTier);
    }

    /** 指定玩家阶级下的冷却秒数（需求 5.1.3 e） */
    public int cooldownSeconds(MagicRank rank) {
        return tier.cooldownSeconds(rank);
    }

    /** 指定玩家阶级下的施法时间（需求 5.1.3 a） */
    public double castSeconds(MagicRank rank) {
        return tier.castSeconds(rank);
    }

    /**
     * 指定玩家阶级下的施法范围（格）。
     *
     * <p>范围由<b>玩家阶级</b>决定（初阶 10 / 中阶 15 / 高阶 20 / 超阶 30），
     * 与技能本身的阶无关——中阶玩家放初阶技能也是 15 格。</p>
     */
    public double range(MagicRank rank) {
        return rank.castRange();
    }

    /** 完整显示名：雷系·初阶·蟒痕 */
    public String fullName() {
        return element.display() + "·" + tier.display() + "·" + name;
    }
}
