package com.cqgzs.cqlove.client;

import net.minecraft.network.chat.Component;

/**
 * 客户端本地提示工具（兼容旧调用点）。
 *
 * <p>早期版本把提示加进聊天框，但 {@code Player.sendSystemMessage()} 在客户端是空实现，
 * 且现在需求是「所有提示画在主界面中下 HUD、不进对话框」，因此这里直接委托给
 * {@link ClientNotices}，由 {@link NoticeHud} 渲染。</p>
 */
public final class ClientChat {

    private ClientChat() {
    }

    /** 在主界面中下位置显示一条本地提示 */
    public static void message(Component component) {
        ClientNotices.push(component);
    }

    /** 在主界面中下位置显示一条本地纯文本提示 */
    public static void message(String text) {
        ClientNotices.push(Component.literal(text));
    }
}
