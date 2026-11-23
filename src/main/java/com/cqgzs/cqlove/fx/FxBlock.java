package com.cqgzs.cqlove.fx;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.network.VisualFxPayload;
import com.cqgzs.cqlove.fx.FxBlock;

/**
 * 「真实方块」特效句柄：对应客户端的 {@code Display.BlockDisplay}
 * （植物系的藤蔓 / 原木 / 树叶、火系的岩浆块弹道都用它）。
 *
 * <p>与贴图面片的区别：方块模型有厚度、有原版几何，玩家绕着走也不会穿帮。</p>
 */
public final class FxBlock extends FxHandle {

    private BlockState state;
    private final boolean glow;

    FxBlock(ServerLevel level, int id, Vec3 pos, BlockState state,
            float scaleX, float scaleY, float scaleZ,
            @Nullable Quaternionf rotation, boolean glow) {
        super(level, id, VisualFxPayload.KIND_BLOCK, pos, scaleX, scaleY, scaleZ, rotation);
        this.state = state;
        this.glow = glow;
    }

    public BlockState blockState() {
        return state;
    }

    /** 换方块（藤蔓转枯枝这类形态变化用；多数技能不需要） */
    public void setBlockState(BlockState state) {
        this.state = state;
        if (alive) {
            FxNet.broadcast(level, pos, spawnPayload());
        }
    }

    @Override
    protected VisualFxPayload spawnPayload() {
        return VisualFxPayload.spawnBlock(id, pos.x, pos.y, pos.z,
                scaleX, scaleY, scaleZ, rotX, rotY, rotZ, rotW,
                lifeTicks, 0, glow, state);
    }

    @Override
    protected VisualFxPayload updatePayload() {
        return VisualFxPayload.update(id, VisualFxPayload.KIND_BLOCK, pos.x, pos.y, pos.z,
                scaleX, scaleY, scaleZ, rotX, rotY, rotZ, rotW, lifeTicks, 0);
    }
}
