package com.cqgzs.cqlove.network;

import com.cqgzs.cqlove.magic.NoticeApi;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.cqgzs.cqlove.magic.MagicCaster;

/**
 * 魔法世界网络包处理：数据同步（下行）与施法请求（上行）。
 */
public final class MagicNetworkHandler {

    private MagicNetworkHandler() {
    }

    /** 客户端：收到服务端推来的魔法数据快照 */
    public static void handleData(MagicDataPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.MagicClientData.accept(payload));
        }
    }

    /** 客户端：收到某玩家的跟随特效开关位（翅膀/法阵渲染层用） */
    public static void handleFx(MagicFxPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.MagicClientFx.accept(payload));
        }
    }

    /** 客户端：收到“某玩家超阶圆环状态”，按服务端给的剩余 tick 画 / 刷新 / 清除法阵 */
    public static void handleCastRing(CastRingPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.MagicClientFx.acceptCastRing(
                    payload.player(), payload.elementOrdinal(), payload.remainingTicks()));
        }
    }

    /** 客户端：收到“某玩家心灵系圆圈状态”，按样式分别缓存，客户端本地倒计时 */
    public static void handleMagicCircle(MagicCirclePayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.MagicClientFx.acceptCircle(
                    payload.player(), payload.variant(), payload.remainingTicks(), payload.radius()));
        }
    }

    /** 客户端：生物身上附着的元素状态（Jade tooltip 显示） */
    public static void handleElementState(ElementStatePayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.ElementClientState.accept(
                    payload.entity(), payload.elements(), payload.remainingTicks()));
        }
    }

    /** 客户端：服务端文字提示 —— 加入队列，画在主界面中下 HUD，不进聊天框 */
    public static void handleClientNotice(ClientNoticePayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.ClientNotices.push(payload.text()));
        }
    }

    /** 客户端：虚拟特效实体（服务端只发描述，客户端自己建实体渲染） */
    public static void handleVisualFx(VisualFxPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.ClientVisualFx.accept(payload));
        }
    }

    /** 服务端：摄魄控心按住 Alt 时的右键移动 / 左键攻击（服务端按玩家视线重新算目标） */
    public static void handleMindControl(MindControlPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> com.cqgzs.cqlove.magic.MindMagic.controlInput(player, payload.action()));
        }
    }

    /** 服务端：拘灵遣将按住 Alt 时的右键吸收 / 左键放出（服务端按玩家视线重算目标） */
    public static void handleCurseControl(CurseControlPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> com.cqgzs.cqlove.magic.CurseMagic.controlInput(player, payload.action()));
        }
    }

    /** 服务端：客户端按下了 Z/X/C/V */
    public static void handleCast(MagicCastPayload payload, IPayloadContext context) {        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> MagicCaster.castSlot(player, payload.slot(), payload.ctrl()));
        }
    }

    /** 服务端：G 轮盘 / P 界面请求切换某系列的当前技能 */
    public static void handleSelect(MagicSelectPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                com.cqgzs.cqlove.magic.MagicElement element =
                        com.cqgzs.cqlove.magic.MagicElement.byId(payload.elementId());
                if (element == null) {
                    return;
                }
                com.cqgzs.cqlove.magic.PlayerMagicData data =
                        com.cqgzs.cqlove.magic.MagicData.get(player);
                // 校验：系列已觉醒、技能已学且属于该系列（防伪造）
                com.cqgzs.cqlove.magic.SkillDefinition skill =
                        com.cqgzs.cqlove.magic.SkillRegistry.byId(payload.skillId());
                if (skill == null || skill.element() != element
                        || !data.hasAwakened(element) || !data.isLearned(skill.id())) {
                    return;
                }
                data.selectSkill(element, skill.id());
                com.cqgzs.cqlove.magic.MagicSync.sync(player);
            });
        }
    }

    /** 服务端：P 界面请求给某项天赋加 1 点 */
    public static void handleTalent(MagicTalentPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                com.cqgzs.cqlove.magic.MagicTalent[] talents =
                        com.cqgzs.cqlove.magic.MagicTalent.values();
                if (payload.talentOrdinal() < 0 || payload.talentOrdinal() >= talents.length) {
                    return;
                }
                com.cqgzs.cqlove.magic.PlayerMagicData data =
                        com.cqgzs.cqlove.magic.MagicData.get(player);
                int used = data.addTalent(talents[payload.talentOrdinal()], 1);
                if (used > 0) {
                    // 速度/力量/血量天赋要写进原版属性
                    com.cqgzs.cqlove.magic.MagicAttributes.apply(player);
                    com.cqgzs.cqlove.magic.MagicSync.sync(player);
                }
            });
        }
    }

    /** 客户端：使用觉醒书后，打开天赋界面技能页（双击未觉醒系列觉醒） */
    public static void handleOpenTalentScreen(OpenTalentScreenPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            context.enqueueWork(() -> com.cqgzs.cqlove.client.MagicTalentScreen.openFromServer());
        }
    }

    /** 服务端：天赋界面技能页双击系列请求觉醒（消耗 1 本觉醒书，默认学会第一个初阶技能） */
    public static void handleAwaken(MagicAwakenPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                com.cqgzs.cqlove.magic.MagicElement element =
                        com.cqgzs.cqlove.magic.MagicElement.byId(payload.elementId());
                if (element == null) {
                    return;
                }
                com.cqgzs.cqlove.magic.PlayerMagicData data =
                        com.cqgzs.cqlove.magic.MagicData.get(player);
                if (data.hasAwakened(element)) {
                    return; // 已觉醒：静默忽略（双击到已觉醒系列时不扣书）
                }
                // 背包里必须有觉醒书才允许觉醒（防止不开书直接发包白嫖）
                net.minecraft.world.item.ItemStack book = findAwakenBook(player);
                if (book.isEmpty()) {
                    NoticeApi.send(player, net.minecraft.network.chat.Component
                            .literal("背包里没有觉醒书，无法觉醒")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
                String error = MagicCaster.awaken(player, element);
                if (error != null) {
                    NoticeApi.send(player, net.minecraft.network.chat.Component
                            .literal(error).withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
                // 觉醒成功：默认学会该系列第一个初阶技能
                java.util.List<com.cqgzs.cqlove.magic.SkillDefinition> novice =
                        com.cqgzs.cqlove.magic.SkillRegistry.of(element,
                                com.cqgzs.cqlove.magic.SkillTier.NOVICE);
                if (!novice.isEmpty()) {
                    data.learn(novice.get(0).id());
                }
                // 消耗 1 本觉醒书（创造模式不扣）
                if (!player.getAbilities().instabuild) {
                    book.shrink(1);
                }
                com.cqgzs.cqlove.magic.MagicSync.sync(player);
                // 成功反馈只放音效 + 系列色粒子，不刷聊天框
                com.cqgzs.cqlove.item.MagicBookItem.feedback(player, element.color(),
                        net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE, 0.9F);
            });
        }
    }

    /** 在玩家背包里找一本觉醒书（主手/副手优先，然后背包顺序） */
    private static net.minecraft.world.item.ItemStack findAwakenBook(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof com.cqgzs.cqlove.item.MagicBookItem book
                    && book.kind() == com.cqgzs.cqlove.item.MagicBookItem.Kind.AWAKEN) {
                return stack;
            }
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
