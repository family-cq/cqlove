package com.cqgzs.cqlove.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import com.cqgzs.cqlove.network.CurseControlPayload;

/**
 * 诅咒系超阶「拘灵遣将」的客户端输入：按住 Alt 时，右键吸收 / 左键放出并指定目标。
 *
 * <p>与摄魄控心同一套三路冗余：MouseButton.Pre 抓按下沿（瞬时点击不漏）、
 * Key 事件跟踪 Alt、tick 轮询处理按住。包里只带动作，服务端按玩家视线重算。</p>
 *
 * <p>监听器由 {@code PlatformModsClient} 显式挂到游戏事件总线。</p>
 */
public final class CurseControlInput {

    private static final int ACTION_ABSORB = 1;
    private static final int ACTION_RELEASE = 2;

    private static boolean altDown;
    private static boolean leftDown;
    private static boolean rightDown;
    private static int ticks;

    private CurseControlInput() {
    }

    public static void onKey(InputEvent.Key event) {
        int key = event.getKey();
        if (key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT) {
            altDown = event.getAction() != GLFW.GLFW_RELEASE;
            if (!altDown) {
                reset();
            }
        }
    }

    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        boolean alt = altDown
                || (event.getModifiers() & GLFW.GLFW_MOD_ALT) != 0
                || rawAlt(minecraft);
        if (!alt) {
            return;
        }
        int button = event.getButton();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            send(ACTION_ABSORB, true);
            rightDown = true;
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            send(ACTION_RELEASE, true);
            leftDown = true;
        }
    }

    /** 客户端每 tick 调一次（由 {@link MagicKeybinds#onClientTick()} 驱动） */
    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.screen != null) {
            reset();
            return;
        }
        boolean alt = altDown || rawAlt(minecraft);
        if (!alt) {
            reset();
            return;
        }
        long handle = minecraft.getWindow().handle();
        boolean left = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        // 右键：按住时每 20 tick 尝试再吸收（避免一瞬间连吸一堆）
        if (right && (!rightDown || ticks % 20 == 0)) {
            send(ACTION_ABSORB, false);
        }
        // 左键：按住时每 10 tick 补一次目标指定
        if (left && (!leftDown || ticks % 10 == 0)) {
            send(ACTION_RELEASE, false);
        }
        rightDown = right;
        leftDown = left;
        ticks++;
    }

    private static boolean rawAlt(Minecraft minecraft) {
        var window = minecraft.getWindow();
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)) {
            return true;
        }
        long handle = window.handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    private static void send(int action, boolean edge) {
        if (edge) {
            com.cqgzs.cqlove.PlatformMods.LOGGER.debug("[cqlove] 拘灵遣将指令 action={} (Alt+鼠标)", action);
        }
        ClientPacketDistributor.sendToServer(new CurseControlPayload(action));
    }

    private static void reset() {
        leftDown = false;
        rightDown = false;
        ticks = 0;
    }
}
