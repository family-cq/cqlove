package com.cqgzs.cqlove.magic;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.fx.FxBillboard;
import com.cqgzs.cqlove.fx.FxNet;

/**
 * 地面贴图（法阵 / 岩浆等「图片式」特效）。
 *
 * <p>与 {@link DisplayFx} 同源：把一张平铺的 16×16 面片（见
 * {@code assets/cqlove/models/item/*.json}）放大到指定大小、贴着地面。
 * 26.1.2 起同样改为<b>客户端虚拟实体</b>：服务端只发一个描述包，
 * 客户端自己建 {@code FxBillboard}，服务端世界里没有这个实体。</p>
 */
public final class GroundDecal {

    private GroundDecal() {
    }

    /**
     * 在指定位置生成一张平铺在地面的图片。
     *
     * @param level 服务端世界
     * @param pos   位置（已经贴地的坐标）
     * @param item  用来承载贴图的物品（模型必须是平铺面片）
     * @param size  图片边长（格）
     */
    public static FxBillboard spawn(ServerLevel level, Vec3 pos, Item item, float size) {
        return FxNet.billboard(level, pos, item, size, 1.0F, size,
                Display.BillboardConstraints.FIXED, null);
    }

    /**
     * 从给定位置往下找「脚下地面」：返回该地面方块顶面往上 0.02 格的位置。
     * 水面上也认（水面当作地面）；找不到就退回原位置。
     */
    public static Vec3 groundBelow(ServerLevel level, Vec3 pos, int maxDepth) {
        BlockPos base = BlockPos.containing(pos);
        for (int i = 0; i <= maxDepth; i++) {
            BlockPos probe = base.below(i);
            BlockState state = level.getBlockState(probe);
            boolean solid = state.isSolidRender();
            boolean fluid = !state.getFluidState().isEmpty();
            if (solid || fluid) {
                return new Vec3(pos.x, probe.getY() + 1.0D + 0.02D, pos.z);
            }
        }
        return pos;
    }

    /** 兼容旧调用：允许传 null 的贴图句柄 */
    public static boolean isAlive(@Nullable FxBillboard display) {
        return display != null && display.isAlive();
    }
}
