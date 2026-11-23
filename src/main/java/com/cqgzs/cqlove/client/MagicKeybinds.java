package com.cqgzs.cqlove.client;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import net.minecraft.network.chat.Component;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.magic.SkillDefinition;
import com.cqgzs.cqlove.network.MagicCastPayload;

/**
 * 魔法世界按键：
 * <ul>
 *   <li>Z / X / C / V：两段式施法——第一次按选中第 1~4 个觉醒系列，再按同一键释放其当前技能</li>
 *   <li>P：打开天赋界面（需求 5.1）</li>
 *   <li>G：按住打开技能轮盘，松开时选择（需求 5.10）</li>
 * </ul>
 *
 * <p>施法只发“槽位”给服务端，由服务端决定该槽位是哪个系列、哪个技能，防止客户端伪造。
 * 任何界面打开时（对话框/背包/暂停）都不触发（需求 一.6）。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID, value = Dist.CLIENT)
public final class MagicKeybinds {

    /** 四个技能槽 */
    public static final KeyMapping[] SLOTS = new KeyMapping[4];
    /** P：天赋界面 */
    public static KeyMapping TALENT;
    /** G：技能轮盘 */
    public static KeyMapping WHEEL;

    /** 默认按键：Z X C V */
    private static final int[] DEFAULT_KEYS = {
            GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_V
    };

    private MagicKeybinds() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (int slot = 0; slot < 4; slot++) {
            // key.cqlove.magic_slot_1 ... 4，语言文件里给出中文名
            SLOTS[slot] = new KeyMapping("key.cqlove.magic_slot_" + (slot + 1),
                    DEFAULT_KEYS[slot], KeyMapping.Category.GAMEPLAY);
            event.register(SLOTS[slot]);
        }

        TALENT = new KeyMapping("key.cqlove.magic_talent", GLFW.GLFW_KEY_P, KeyMapping.Category.GAMEPLAY);
        WHEEL = new KeyMapping("key.cqlove.magic_wheel", GLFW.GLFW_KEY_G, KeyMapping.Category.GAMEPLAY);
        event.register(TALENT);
        event.register(WHEEL);
    }

    /** 客户端每 tick 调一次：处理按键 + 冷却倒计时 */
    public static void onClientTick() {
        MagicClientData.tick();

        Minecraft minecraft = Minecraft.getInstance();
        // 心灵系·摄魄控心：按住 Alt 时右键移动 / 左键攻击被控制的生物
        MindControlInput.tick(minecraft);
        // 诅咒系·拘灵遣将：按住 Alt 时右键吸收 / 左键放出并指定目标
        CurseControlInput.tick(minecraft);
        // 有界面（聊天框/背包/暂停）或未进入世界时不响应
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }

        // P：天赋界面
        if (TALENT != null) {
            while (TALENT.consumeClick()) {
                minecraft.setScreen(new MagicTalentScreen());
            }
        }

        // G：技能轮盘（没有已学技能就不打开，避免开了个空轮盘）
        if (WHEEL != null) {
            while (WHEEL.consumeClick()) {
                if (!MagicClientData.allLearnedSkills().isEmpty()) {
                    minecraft.setScreen(new SkillWheelScreen());
                }
            }
        }

        // Z/X/C/V：两段式施法——第一次按选中该槽位系列，再按同一键才释放
        for (int slot = 0; slot < 4; slot++) {
            KeyMapping mapping = SLOTS[slot];
            if (mapping == null) {
                continue;
            }
            while (mapping.consumeClick()) {
                if (MagicClientData.armedSlot() != slot) {
                    // 第一次按：只选中，不释放；用屏幕中央下方的提示条告知当前选择
                    MagicClientData.armedSlot(slot);
                    MagicElement element = MagicClientData.elementAt(slot);
                    if (element != null) {
                        SkillDefinition skill = MagicClientData.selectedSkill(element);
                        String skillName = skill != null ? skill.name() : "无技能";
                        minecraft.gui.setOverlayMessage(Component.literal(
                                "已选择 " + element.display() + " · " + skillName + "，再按一次释放"), false);
                    }
                } else {
                    // 第二次按：释放该槽位系列的当前技能；附带 Ctrl 状态（石盾等技能区分对自己/对目标）
                    com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
                    boolean ctrl = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                            || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
                    ClientPacketDistributor.sendToServer(new MagicCastPayload(slot, ctrl));
                }
            }
        }
    }
}
