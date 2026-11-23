package com.cqgzs.cqlove.lan;

import net.minecraft.network.chat.Component;

/**
 * 联机模式（需求文档 二.2.g）。
 *
 * <ul>
 *   <li>{@link #ONLINE} 在线模式：需要正版游戏验证才能进入游戏（默认）。</li>
 *   <li>{@link #OFFLINE_UUID_FIX} UUID修复+离线模式：非正版玩家也可以加入游戏，
 *       并对其 UUID 进行修复（优先使用 Mojang 官方 UUID，见 {@link UuidFixer}）。</li>
 * </ul>
 */
public enum OnlineModeSetting {

    /** 在线模式（默认）：服务器开启正版验证 */
    ONLINE("online", "在线模式"),
    /** UUID修复+离线模式：关闭正版验证，并尝试将玩家 UUID 修复为官方 UUID */
    OFFLINE_UUID_FIX("offline_uuid_fix", "UUID修复+离线模式");

    /** 用于 JSON 序列化的稳定 id */
    private final String id;
    /** 循环按钮上显示的模式名（界面标签前缀“联机模式”由按钮 caption 提供） */
    private final Component shortName;

    OnlineModeSetting(String id, String shortText) {
        this.id = id;
        this.shortName = Component.literal(shortText);
    }

    public String getId() {
        return id;
    }

    /** 模式短名（“在线模式”/“UUID修复+离线模式”），配合按钮 caption 显示为“联机模式: xxx” */
    public Component getShortName() {
        return shortName;
    }

    /** 是否为在线（正版验证）模式 */
    public boolean isOnline() {
        return this == ONLINE;
    }

    /** 根据 JSON id 反序列化，无法识别时回退默认在线模式 */
    public static OnlineModeSetting byId(String id) {
        for (OnlineModeSetting mode : values()) {
            if (mode.id.equalsIgnoreCase(id)) {
                return mode;
            }
        }
        return ONLINE;
    }
}
