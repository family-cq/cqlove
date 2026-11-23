package com.cqgzs.cqlove.lan;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.cqgzs.cqlove.PlatformMods;

/**
 * PVP API（需求文档 二.2.h）。
 *
 * <p>“允许PVP”开关的统一判定入口，后续所有可能造成玩家间伤害的逻辑
 * （近战、弓箭、以及魔法世界的各系技能伤害）都必须先调用
 * {@link #canHurt(Entity, Entity)} 进行判断：</p>
 * <ul>
 *   <li>PVP 关闭：玩家与玩家之间无法造成任何伤害；</li>
 *   <li>PVP 开启：玩家之间可以正常造成伤害。</li>
 * </ul>
 *
 * <p>本类同时注册了一个事件监听器，拦截原版途径（近战/弹射物等）造成的
 * 玩家间伤害；魔法技能在结算时也应主动调用本 API，保证表现一致。</p>
 *
 * <p>开关状态不持久化到存档：每次“创建局域网”时由界面设置；
 * 单人未开放局域网时开关无实际影响（没有第二个玩家）。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class PvpApi {

    /** 当前是否允许 PVP（需求文档：默认关闭） */
    private static volatile boolean pvpAllowed = false;

    private PvpApi() {
    }

    /** 当前是否允许 PVP */
    public static boolean isPvpAllowed() {
        return pvpAllowed;
    }

    /** 设置是否允许 PVP（“创建局域网”按钮点击时调用） */
    public static void setPvpAllowed(boolean allowed) {
        pvpAllowed = allowed;
        PlatformMods.LOGGER.info("[cqlove] PVP 开关已设置为: {}", allowed);
    }

    /**
     * 统一伤害判定入口：攻击者能否对目标造成伤害。
     *
     * <p>仅当攻击者和目标<b>都是玩家</b>时受 PVP 开关约束；
     * 其余情况（玩家打怪、怪打玩家等）不受影响。</p>
     *
     * @param attacker 伤害来源实体（可为 null，表示无明确攻击者）
     * @param target   受伤实体
     * @return true 允许造成伤害；false 应拦截本次伤害
     */
    public static boolean canHurt(Entity attacker, Entity target) {
        if (attacker instanceof Player && target instanceof Player) {
            return pvpAllowed;
        }
        return true;
    }

    /**
     * 拦截原版途径的玩家间伤害（近战、箭矢、雪球等）。
     * PVP 关闭时直接取消伤害事件。
     */
    @SubscribeEvent
    public static void onPlayerAttackPlayer(LivingIncomingDamageEvent event) {
        // 事件目标不是玩家时不关心
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (!canHurt(attacker, event.getEntity())) {
            // PVP 关闭：取消本次玩家间伤害
            event.setCanceled(true);
        }
    }
}
