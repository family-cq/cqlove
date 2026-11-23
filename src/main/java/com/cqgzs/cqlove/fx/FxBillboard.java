package com.cqgzs.cqlove.fx;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.network.VisualFxPayload;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 「图片式」特效句柄：对应客户端的 {@code Display.ItemDisplay} 面片
 * （法阵 / 冰墙 / 旋风 / 水墙 / 光柱都用它）。
 */
public final class FxBillboard extends FxHandle {

    private ItemStack item;
    private final Display.BillboardConstraints billboard;
    private final boolean glow;
    private int interpolationTicks;

    FxBillboard(ServerLevel level, int id, Vec3 pos, ItemStack item,
                float scaleX, float scaleY, float scaleZ,
                Display.BillboardConstraints billboard, @Nullable Quaternionf rotation) {
        super(level, id, VisualFxPayload.KIND_BILLBOARD, pos, scaleX, scaleY, scaleZ, rotation);
        this.item = item;
        this.billboard = billboard;
        this.glow = true;
    }

    public ItemStack item() {
        return item;
    }

    /**
     * 改变贴图面片的形状 / 朝向 / 展示物品。
     *
     * @param interpolationTicks 客户端过渡时长（tick），0 = 瞬间
     */
    public void reshape(ItemStack item, float scaleX, float scaleY, float scaleZ,
                        @Nullable Quaternionf rotation, int interpolationTicks) {
        if (!alive) {
            return;
        }
        // 换贴图必须重发完整描述：update 包不带模型，客户端会用旧贴图
        boolean itemChanged = !ItemStack.matches(this.item, item);
        this.item = item;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
        this.interpolationTicks = Math.max(0, interpolationTicks);
        setRotation(rotation);
        FxNet.broadcast(level, pos, itemChanged ? spawnPayload() : updatePayload());
    }

    @Override
    protected VisualFxPayload spawnPayload() {
        return VisualFxPayload.spawnBillboard(id, pos.x, pos.y, pos.z,
                scaleX, scaleY, scaleZ, rotX, rotY, rotZ, rotW,
                lifeTicks, interpolationTicks, billboard.ordinal(), glow, item);
    }

    @Override
    protected VisualFxPayload updatePayload() {
        return VisualFxPayload.update(id, VisualFxPayload.KIND_BILLBOARD, pos.x, pos.y, pos.z,
                scaleX, scaleY, scaleZ, rotX, rotY, rotZ, rotW, lifeTicks, interpolationTicks);
    }
}
