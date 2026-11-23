package com.cqgzs.cqlove.fx;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.network.VisualFxPayload;

/**
 * 「文本」特效句柄：对应客户端的 {@code Display.TextDisplay}（伤害数字）。
 *
 * <p>同样只在服务端留一个描述 + id，客户端自己建实体渲染。
 * 文本内容随 spawn 包一次性发过去，后续只更新位置。</p>
 */
public final class FxText extends FxHandle {

    private final Component text;
    private final Display.BillboardConstraints billboard;
    private final int background;

    FxText(ServerLevel level, int id, Vec3 pos, Component text, float scale,
           Display.BillboardConstraints billboard, int background, int lifeTicks) {
        super(level, id, VisualFxPayload.KIND_TEXT, pos, scale, scale, scale, null);
        this.text = text;
        this.billboard = billboard;
        this.background = background;
        this.lifeTicks = Math.max(1, lifeTicks);
    }

    public Component text() {
        return text;
    }

    @Override
    protected VisualFxPayload spawnPayload() {
        return VisualFxPayload.spawnText(id, pos.x, pos.y, pos.z, scaleX, scaleY, scaleZ,
                lifeTicks, 3, billboard.ordinal(), background, text);
    }

    @Override
    protected VisualFxPayload updatePayload() {
        return VisualFxPayload.update(id, VisualFxPayload.KIND_TEXT, pos.x, pos.y, pos.z,
                scaleX, scaleY, scaleZ, rotX, rotY, rotZ, rotW, lifeTicks, 0);
    }
}
