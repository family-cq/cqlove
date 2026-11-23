package com.cqgzs.cqlove.magic;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.fx.FxBillboard;
import com.cqgzs.cqlove.fx.FxNet;

/**
 * 通用「图片式」特效（风系旋风柱 / 撕裂空间 / 翅膀 / 风盾都用它）。
 *
 * <p><b>26.1.2 起改为客户端虚拟实体</b>：这里不再往服务端世界 spawn
 * {@code FxBillboard}，只生成一个 {@link FxBillboard} 句柄并发包，
 * 由客户端自己创建实体、自己渲染。服务端因此不再有实体 tick 与实体同步开销，
 * 但调用方看到的 API 与以前一致（返回句柄 + setPos / reshape / discard）。</p>
 *
 * <p>面片用 billboard 让模型始终面向相机（{@link Display.BillboardConstraints#CENTER}），
 * 所以不用每 tick 改旋转，位置变化走客户端插值。</p>
 */
public final class DisplayFx {

    private DisplayFx() {
    }

    /**
     * 生成一个 billboard 贴图特效。
     *
     * @param scaleX 横向缩放（格）
     * @param scaleY 纵向缩放（格）
     * @param scaleZ 前后缩放（格，单面片时无影响）
     */
    public static FxBillboard spawn(ServerLevel level, Vec3 pos, Item item,
                                    float scaleX, float scaleY, float scaleZ,
                                    Display.BillboardConstraints billboard) {
        return spawn(level, pos, item, scaleX, scaleY, scaleZ, billboard, null);
    }

    /**
     * 生成一个贴图特效，并用 {@code rotation} 给面片定初始朝向（水墙/波浪这类要固定朝向的用它）。
     *
     * @param rotation 附加在缩放之前的基础旋转；传 null 表示不旋转（面片朝南北）
     */
    public static FxBillboard spawn(ServerLevel level, Vec3 pos, Item item,
                                    float scaleX, float scaleY, float scaleZ,
                                    Display.BillboardConstraints billboard,
                                    @Nullable Quaternionf rotation) {
        return FxNet.billboard(level, pos, item, scaleX, scaleY, scaleZ, billboard, rotation);
    }

    /** 水平朝向用：把 yaw（弧度，0 = 面片朝 +Z）转成四元数 */
    public static Quaternionf yawRotation(double radians) {
        return new Quaternionf().rotateY((float) radians);
    }

    /**
     * 改造一个已存在的贴图特效的缩放（画壁「竖线 → 左右展开」这类形变动画用）。
     *
     * <p>客户端会按 {@code interpolationTicks} 平滑过渡；模型物品必须一起带上，
     * 否则客户端重灌 NBT 时会被 {@code ItemDisplay} 清成空栈（画壁展开后没图的老问题）。</p>
     *
     * @param stack              当前展示物品
     * @param rotation           基础朝向（与生成时保持一致）
     * @param interpolationTicks 过渡时长（tick），0 表示瞬间改变
     */
    public static void reshape(FxBillboard display, ServerLevel level, ItemStack stack,
                               float scaleX, float scaleY, float scaleZ,
                               @Nullable Quaternionf rotation, int interpolationTicks) {
        display.reshape(stack, scaleX, scaleY, scaleZ, rotation, interpolationTicks);
    }
}
