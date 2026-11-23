package com.cqgzs.cqlove.magic;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 18 个系列各自的魔法伤害类型（需求 2026-11-13：什么系列的技能打出去就是什么系列的魔法伤害）。
 *
 * <p>注册方式是纯数据驱动：{@code data/cqlove/damage_type/magic_<系列id>.json}，
 * 这里只按 {@link MagicElement} 拼出对应的 {@link ResourceKey}。
 * 所有元素伤害类型都写进了 {@code minecraft:bypasses_armor} 标签，
 * 与原来用的 {@code minecraft:indirect_magic} 行为保持一致（护甲不减魔法伤害）。</p>
 *
 * <p>好处：死亡提示能看出是被哪一系打死（{@code death.attack.cqlove.magic_fire}），
 * 其他模组/插件也能按伤害类型区分元素，后续要加“元素抗性/元素免疫”直接查类型即可。</p>
 */
public final class MagicDamageTypes {

    private static final Map<MagicElement, ResourceKey<DamageType>> KEYS =
            new EnumMap<>(MagicElement.class);

    static {
        for (MagicElement element : MagicElement.values()) {
            KEYS.put(element, ResourceKey.create(Registries.DAMAGE_TYPE,
                    Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_" + element.id())));
        }
    }

    private MagicDamageTypes() {
    }

    /** 某个系列的伤害类型 key */
    public static ResourceKey<DamageType> key(MagicElement element) {
        return KEYS.get(element);
    }

    /** 以某个实体系列魔法来源构造伤害源（direct / causing 都是该实体，便于击杀归属与死亡提示） */
    public static DamageSource source(ServerLevel level, MagicElement element, Entity cause) {
        return level.damageSources().source(key(element), cause, cause);
    }

    /**
     * 这个伤害源是不是“本模组的元素魔法伤害”（{@code cqlove:magic_*}）。
     *
     * <p>风之翼魔抗、物理伤害数字过滤等判定要认它——原来的写法只认
     * {@code minecraft:indirect_magic}，换成元素类型后会漏判。</p>
     */
    public static boolean isElementMagic(DamageSource source) {
        return source.typeHolder().unwrapKey()
                .map(key -> PlatformMods.MODID.equals(key.identifier().getNamespace())
                        && key.identifier().getPath().startsWith("magic_"))
                .orElse(false);
    }

    /** 从伤害类型反解元素（非本模组元素魔法返回 null） */
    public static MagicElement elementOf(DamageSource source) {
        return source.typeHolder().unwrapKey()
                .map(key -> {
                    if (!PlatformMods.MODID.equals(key.identifier().getNamespace())
                            || !key.identifier().getPath().startsWith("magic_")) {
                        return null;
                    }
                    String id = key.identifier().getPath().substring("magic_".length());
                    return MagicElement.byId(id);
                })
                .orElse(null);
    }
}
