package com.cqgzs.cqlove.magic;

/**
 * 技能释放逻辑（函数式接口）。
 *
 * <p>所有技能只拿到一个 {@link SkillContext}，耗蓝/冷却/权限校验已在
 * {@link MagicCaster} 里做完了，技能实现里只关心“表现 + 伤害”。</p>
 */
@FunctionalInterface
public interface SkillAction {

    void cast(SkillContext context);
}
