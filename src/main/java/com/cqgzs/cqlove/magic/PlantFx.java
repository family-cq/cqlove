package com.cqgzs.cqlove.magic;

import java.util.List;

import org.joml.Quaternionf;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.fx.FxBlock;
import com.cqgzs.cqlove.fx.FxNet;

/**
 * 植物系的<b>真实方块模型</b>特效：把真正的方块（藤蔓 / 原木 / 树叶 / 花丛）
 * 摆到世界里，由客户端原版方块渲染器绘制。
 *
 * <p>为什么不用 {@link DisplayFx} 的贴图面片：贴图永远是「一张纸」，只有正面像、侧面穿帮；
 * 方块模型有厚度、有原版的草叶/树干/树叶几何，玩家绕着走也成立。</p>
 *
 * <p>26.1.2 起改为<b>客户端虚拟实体</b>：服务端不生成 {@code FxBlock}，
 * 只发一个描述包（方块 + 缩放 + 朝向），客户端自己建实体渲染。
 * 因此形体在生成时定死，运行期动画一律靠改位置（{@link #move}）。</p>
 */
public final class PlantFx {

    private PlantFx() {
    }

    /** 等比缩放的方块 */
    public static FxBlock block(ServerLevel level, Vec3 pos, Block block,
                                float scale, boolean glow) {
        return block(level, pos, block.defaultBlockState(), scale, scale, scale, null, glow);
    }

    /** 可分别缩放、可带初始朝向的方块（树枝斜插 / 树冠压扁 / 圆木躺倒都靠它） */
    public static FxBlock block(ServerLevel level, Vec3 pos, BlockState state,
                                float scaleX, float scaleY, float scaleZ,
                                @org.jspecify.annotations.Nullable Quaternionf rotation, boolean glow) {
        return FxNet.block(level, pos, state, scaleX, scaleY, scaleZ, rotation, glow);
    }

    /** 把方块移动到新位置（客户端按 teleport_duration 平滑插值） */
    public static void move(FxBlock display, Vec3 pos) {
        if (display != null) {
            display.setPos(pos);
        }
    }

    /** 统一回收一批方块特效 */
    public static void discardAll(List<FxBlock> displays) {
        for (FxBlock display : displays) {
            if (display != null) {
                display.discard();
            }
        }
        displays.clear();
    }

    /** 水平面内朝向：yaw 弧度（0 = 朝向 +Z） */
    public static Quaternionf yaw(float radians) {
        return new Quaternionf().rotateY(radians);
    }

    /** 斜插：先绕 Y 转 yaw，再绕 Z 倾斜 tilt（树枝/根系朝外倒） */
    public static Quaternionf tilt(float yaw, float tilt) {
        return new Quaternionf().rotateY(yaw).rotateZ(tilt);
    }

    /** 躺倒：绕 X 转 90°（圆木平放当横梁） */
    public static Quaternionf flat() {
        return new Quaternionf().rotateX((float) (Math.PI / 2.0D));
    }
}
