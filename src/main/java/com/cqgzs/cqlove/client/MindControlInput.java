package com.cqgzs.cqlove.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import com.cqgzs.cqlove.network.MindControlPayload;

/**
 * 心灵系高阶「摄魄控心」的客户端输入：按住 Alt 时，右键移动 / 左键攻击被控制的生物。
 *
 * <p>修复 2026-11-14：原来只在 {@code ClientTickEvent.Post} 里轮询 GLFW 鼠标状态，
 * 一次快速点击（按下与松开落在同一个 50ms tick 内）会被完全漏掉，表现为「按住 Alt 没反应」。
 * 现在改为三路冗余：</p>
 * <ol>
 *   <li>{@link InputEvent.MouseButton.Pre} 抓「按下沿」，瞬时点击也必发一次包；</li>
 *   <li>{@link InputEvent.Key} 跟踪 Alt 按下/松开，配合鼠标事件的 modifier 位；</li>
 *   <li>{@link #tick(Minecraft)} 继续轮询 GLFW，负责「按住不放」时的持续移动 / 连击。</li>
 * </ol>
 *
 * <p>包里只带动作，不带坐标；服务端按玩家自己的视线与施法范围重新算，防伪造。
 * 监听器由 {@code PlatformModsClient} 显式挂到 NeoForge 游戏事件总线上。</p>
 */
public final class MindControlInput {

    /** 与 MindMagic.CONTROL_MOVE / CONTROL_ATTACK 一致（客户端不引用服务端魔法类） */
    private static final int ACTION_MOVE = 1;
    private static final int ACTION_ATTACK = 2;

    /** 由键盘事件维护的 Alt 状态（某些环境下 glfwGetKey 查询修饰键不可靠） */
    private static boolean altDown;
    private static boolean leftDown;
    private static boolean rightDown;
    private static int ticks;

    private MindControlInput() {
    }

    /** Alt 按下/松开：立即更新状态，松开即停手 */
    public static void onKey(InputEvent.Key event) {
        int key = event.getKey();
        if (key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT) {
            altDown = event.getAction() != GLFW.GLFW_RELEASE;
            if (!altDown) {
                reset();
            }
        }
    }

    /**
     * 鼠标按下沿：即使点击短于一个 tick 也保证发出一次控制指令。
     * 用 Pre 而非 Post，避免被其他 mod 取消的事件影响；Pre 的取消标记不影响本监听器被调用。
     */
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
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            send(ACTION_ATTACK, true);
            // 置位后 tick 轮询不会立刻重复发同一条边沿
            leftDown = true;
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            send(ACTION_MOVE, true);
            rightDown = true;
        }
    }

    /** 客户端每 tick 调一次（由 {@link MagicKeybinds#onClientTick()} 驱动）：处理按住不放 */
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

        // 左键：按住时按攻击间隔补发
        if (left && (!leftDown || ticks % 10 == 0)) {
            send(ACTION_ATTACK, false);
        }
        // 右键：按住时持续更新移动目标
        if (right && (!rightDown || ticks % 4 == 0)) {
            send(ACTION_MOVE, false);
        }
        leftDown = left;
        rightDown = right;
        ticks++;
    }

    /** 直接查 GLFW 的 Alt 键状态，作为键盘事件之外的兜底 */
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
            // 留痕：万一还收不到控制，先看 debug.log 里有没有这行，就能区分「没发包」和「服务端没执行」
            com.cqgzs.cqlove.PlatformMods.LOGGER.debug("[cqlove] 摄魄控心指令 action={} (Alt+鼠标)", action);
        }
        ClientPacketDistributor.sendToServer(new MindControlPayload(action));
    }

    private static void reset() {
        leftDown = false;
        rightDown = false;
        ticks = 0;
    }
}
