package com.cqgzs.cqlove.magic;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 把天赋里的“速度 / 力量 / 血量”写进原版属性（需求 5.1.2）。
 *
 * <p>用固定 id 的修饰符反复覆盖：每次数据变化先移除旧修饰符再加新的，
 * 不会越叠越多，也不会影响其他 mod 的属性加成。</p>
 */
public final class MagicAttributes {

    /** 速度天赋修饰符 id */
    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_talent_speed");
    /** 力量天赋修饰符 id */
    private static final Identifier STRENGTH_ID = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_talent_strength");
    /** 血量天赋修饰符 id */
    private static final Identifier HEALTH_ID = Identifier.fromNamespaceAndPath(PlatformMods.MODID, "magic_talent_health");

    private MagicAttributes() {
    }

    /** 按当前天赋重新计算并应用属性（登录、加点、升阶后调用） */
    public static void apply(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);

        double speed = MagicTalent.SPEED.valueAt(data.talentLevel(MagicTalent.SPEED)) * MagicTalent.SPEED.attributeScale();
        double strength = MagicTalent.STRENGTH.valueAt(data.talentLevel(MagicTalent.STRENGTH));
        double health = MagicTalent.HEALTH.valueAt(data.talentLevel(MagicTalent.HEALTH));

        applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID, speed);
        applyModifier(player, Attributes.ATTACK_DAMAGE, STRENGTH_ID, strength);
        applyModifier(player, Attributes.MAX_HEALTH, HEALTH_ID, health);

        // 血量上限变化后把当前血量夹回合法范围，避免“加了上限后剩半颗心”看起来像 bug
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    /** 覆盖式写入一个属性修饰符 */
    private static void applyModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        if (Math.abs(amount) > 1.0E-6D) {
            instance.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
