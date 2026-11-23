package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.fx.FxBillboard;
import com.cqgzs.cqlove.fx.FxNet;
import com.cqgzs.cqlove.item.MagicItems;

/**
 * 元素反应（晶核）生成的护盾：抵挡一次魔法攻击，围绕玩家旋转的发光面片。
 *
 * <p>每种元素盾对应一张贴图（雷电/火/水/土/冰/毒/草），由客户端虚拟 Display 渲染。
 * 每次授予增加 1 层；抵挡一次魔法攻击消耗 1 层并碎裂。</p>
 */
public final class ElementShield {

    /** 一只玩家的盾状态 */
    private static final class State {
        final ServerPlayerRef ref;
        final MagicElement element;
        final FxBillboard visual;
        int charges = 1;
        long bornTick;
        int angle;

        State(ServerPlayerRef ref, MagicElement element, FxBillboard visual, long bornTick) {
            this.ref = ref;
            this.element = element;
            this.visual = visual;
            this.bornTick = bornTick;
        }
    }

    /** 轻量玩家引用（避免长期持有 Player 对象） */
    private record ServerPlayerRef(UUID id) {
    }

    private static final Map<UUID, State> SHIELDS = new ConcurrentHashMap<>();

    private ElementShield() {
    }

    /** 玩家 id → 盾的元素（供伤害拦截读取） */
    public static MagicElement elementOf(UUID playerId) {
        State s = SHIELDS.get(playerId);
        return s == null ? null : s.element;
    }

    /** 给玩家加一层元素盾（同种元素叠加层数，不同种替换） */
    public static void grant(LivingEntity owner, MagicElement element) {
        if (!(owner.level() instanceof ServerLevel level)) {
            return;
        }
        UUID id = owner.getUUID();
        long now = level.getGameTime();
        State existing = SHIELDS.get(id);
        if (existing != null) {
            if (existing.element == element) {
                existing.charges++;
                return;
            }
            // 不同元素：丢弃旧盾
            existing.visual.discard();
        }
        var item = shieldItem(element);
        if (item == null) {
            return;
        }
        FxBillboard visual = FxNet.billboard(level, owner.position().add(0.0D, 1.2D, 0.0D),
                item, 1.1F, 1.1F, 1.0F, Display.BillboardConstraints.CENTER, null);
        SHIELDS.put(id, new State(new ServerPlayerRef(id), element, visual, now));
        net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.literal(
                element.display() + "盾已生成 (抵挡一次魔法攻击)")
                .withStyle(net.minecraft.ChatFormatting.GRAY);
        if (owner instanceof net.minecraft.server.level.ServerPlayer player) {
            NoticeApi.send(player, msg);
        }
    }

    /**
     * 尝试用元素盾抵挡一次魔法攻击。
     *
     * @return true 表示本次魔法攻击已被抵挡
     */
    public static boolean tryBlock(LivingEntity target, ServerLevel level) {
        State s = SHIELDS.get(target.getUUID());
        if (s == null || s.charges <= 0) {
            return false;
        }
        s.charges--;
        MagicDamage.playSound(level, target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D),
                SoundEvents.GLASS_BREAK, 0.8F, 1.4F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                target.getX(), target.getY() + target.getBbHeight() * 0.6D, target.getZ(),
                16, 0.5D, 0.5D, 0.5D, 0.05D);
        if (s.charges <= 0) {
            s.visual.discard();
            SHIELDS.remove(target.getUUID());
        }
        return true;
    }

    /** 由 MagicEvents 每 tick 调用，传入任一 ServerLevel 以驱动旋转与失效清理 */
    public static void tickShields(ServerLevel level) {
        Iterator<Map.Entry<UUID, State>> iterator = SHIELDS.entrySet().iterator();
        while (iterator.hasNext()) {
            State s = iterator.next().getValue();
            LivingEntity holder = level.getEntity(s.ref.id()) instanceof LivingEntity living ? living : null;
            if (holder == null || !holder.isAlive() || holder.level() != level) {
                s.visual.discard();
                iterator.remove();
                continue;
            }
            s.angle = (s.angle + 3) % 360;
            double radians = Math.toRadians(s.angle);
            Vec3 orbit = holder.position().add(Math.cos(radians) * 0.9D,
                    holder.getBbHeight() * 0.6D + Math.sin(radians * 2) * 0.15D,
                    Math.sin(radians) * 0.9D);
            s.visual.setPos(orbit);
        }
    }

    private static net.minecraft.world.item.Item shieldItem(MagicElement element) {
        return switch (element) {
            case THUNDER -> MagicItems.SHIELD_THUNDER.get();
            case FIRE -> MagicItems.SHIELD_FIRE.get();
            case WATER -> MagicItems.WATER_SHIELD.get();
            case EARTH -> MagicItems.SHIELD_EARTH.get();
            case ICE -> MagicItems.SHIELD_ICE.get();
            case POISON -> MagicItems.SHIELD_POISON.get();
            case PLANT -> MagicItems.SHIELD_PLANT.get();
            default -> MagicItems.WATER_SHIELD.get(); // 其它元素暂用水盾贴图
        };
    }

    public static void clearAll() {
        for (State s : SHIELDS.values()) {
            s.visual.discard();
        }
        SHIELDS.clear();
    }
}
