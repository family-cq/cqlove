package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.fx.FxNet;
import com.cqgzs.cqlove.fx.FxText;

/**
 * 伤害数值显示（需求：攻击生物后显示伤害数值，0.5 秒后消失）。
 *
 * <p><b>26.1.2 起改为客户端虚拟实体</b>：服务端不再生成 {@code Display.TextDisplay}，
 * 只发一个文本描述包（内容 + 颜色 + 位置），客户端自己建 TextDisplay 渲染。
 * 位置每 tick 上移一小段，客户端按插值平滑上升。</p>
 *
 * <p>颜色规则：物理伤害白色；魔法伤害用系列代表色（由 {@link MagicDamage} 传入）。</p>
 */
public final class DamageNumbers {

    /** 显示时长：1 秒 = 20 tick（留 2 tick 余量给网络抖动） */
    private static final int LIFETIME_TICKS = 22;
    /** 每 tick 上浮高度（格），配合客户端插值平滑上升 */
    private static final double RISE_PER_TICK = 0.05D;
    /** 数字整体缩放：TextDisplay 内部自带 0.025 像素换算，这里放大到醒目尺寸 */
    private static final float SCALE = 1.35F;
    /** 伤害数字在生物头顶之上的额外高度（格） */
    private static final double HEAD_CLEARANCE = 0.55D;
    /** 客户端兜底存活时长：1.4 秒（正常会被 tick 提前 discard） */
    private static final int CLIENT_LIFE_TICKS = 28;
    /** 物理伤害数字颜色（白色） */
    public static final int PHYSICAL_COLOR = 0xFFFFFF;

    /** 还活着的伤害数字句柄 */
    private static final List<Entry> ACTIVE = new ArrayList<>();

    private DamageNumbers() {
    }

    /**
     * 在指定位置弹出一个伤害数字。
     *
     * @param level  服务端世界
     * @param pos    基准位置（一般取目标生物头顶，内部会加少量随机偏移避免重叠）
     * @param amount 伤害数值
     * @param rgb    文本颜色（物理传 {@link #PHYSICAL_COLOR}，魔法传系列代表色）
     */
    public static void spawn(ServerLevel level, Vec3 pos, double amount, int rgb) {
        if (amount <= 0.0D) {
            return;
        }
        // 整数显示不带小数点，小数保留 1 位
        String number = amount == Math.floor(amount)
                ? String.valueOf((int) amount)
                : String.format("%.1f", amount);
        Component text = Component.literal(number)
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true));

        Vec3 at = place(level, pos);
        FxText handle = FxNet.text(level, at, text, SCALE,
                Display.BillboardConstraints.CENTER, 0, CLIENT_LIFE_TICKS);
        ACTIVE.add(new Entry(handle, LIFETIME_TICKS));
    }

    /** 统一定位：在传入的头顶基准之上再抬高一点，水平只留极小抖动，保证数字始终在目标正上方 */
    private static Vec3 place(ServerLevel level, Vec3 pos) {
        double ox = (level.getRandom().nextDouble() - 0.5D) * 0.3D;
        double oz = (level.getRandom().nextDouble() - 0.5D) * 0.3D;
        return new Vec3(pos.x + ox, pos.y + HEAD_CLEARANCE, pos.z + oz);
    }

    /**
     * 弹出一条带名称的伤害数字（元素反应用）：显示为两行「名称」+「数值」。
     *
     * @param label  反应名称（如"超载"）
     * @param rgb    文本颜色
     */
    public static void spawnNamed(ServerLevel level, Vec3 pos, String label, double amount, int rgb) {
        if (amount <= 0.0D) {
            return;
        }
        String number = amount == Math.floor(amount)
                ? String.valueOf((int) amount)
                : String.format("%.1f", amount);
        Component text = Component.literal(label + "  " + number)
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true));

        Vec3 at = place(level, pos);
        FxText handle = FxNet.text(level, at, text, SCALE,
                Display.BillboardConstraints.CENTER, 0, CLIENT_LIFE_TICKS);
        ACTIVE.add(new Entry(handle, LIFETIME_TICKS));
    }

    /** 服务端每 tick 调用：上浮动画 + 到期移除（由 MagicEvents 的服务端 tick 事件驱动） */
    public static void tick() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Entry> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            FxText handle = entry.handle;
            if (!handle.isAlive()) {
                iterator.remove();
                continue;
            }
            handle.setPos(handle.getX(), handle.getY() + RISE_PER_TICK, handle.getZ());
            if (--entry.ticksLeft <= 0) {
                handle.discard();
                iterator.remove();
            }
        }
    }

    /** 服务器关闭时清空引用，防止跨存档残留 */
    public static void clear() {
        ACTIVE.clear();
    }

    /** 一条存活中的伤害数字记录 */
    private static final class Entry {
        private final FxText handle;
        private int ticksLeft;

        private Entry(FxText handle, int ticksLeft) {
            this.handle = handle;
            this.ticksLeft = ticksLeft;
        }
    }
}
