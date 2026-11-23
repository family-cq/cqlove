package com.cqgzs.cqlove.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.network.MagicCirclePayload;

/**
 * 玩家身上的“跟随特效”渲染层：背后六翼（风之翼被动）+ 脚下法阵（火神/雷神 buff）+ 超阶施法圆环。
 *
 * <p>以前这些是服务端 ItemDisplay 实体，每 tick 追一次玩家位置，玩家跑动时特效总比人慢一拍。
 * 现在改成<b>渲染层直接画在玩家模型上</b>（和披风/鞘翅同一条渲染路径），用的是渲染管线
 * 内部插值后的玩家位置，完全没有延迟，弯腰/潜行/飞行时也跟着身体姿态走。</p>
 *
 * <p>该不该画、画哪种，由 {@link MagicClientFx#FLAGS_KEY} / {@link MagicClientFx#CAST_RING_KEY}
 * 渲染状态扩展决定（服务端广播 {@code MagicFxPayload} / {@code CastRingPayload}，见 {@code MagicFxSync}）。</p>
 *
 * <p>本类只从客户端入口（{@code PlatformModsClient}）引用，不会在服务端类加载。</p>
 */
public final class PlayerFxLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

    private static final Identifier WINGS_TEXTURE =
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/wind_wings.png");
    private static final Identifier THUNDER_RING_TEXTURE =
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/thunder_ring.png");
    private static final Identifier FIRE_RING_TEXTURE =
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/fire_ring.png");
    /** 法阵贴图是 32x2048 的 64 帧竖排动画条，这里只取第 1 帧（旋转由渲染层自己做） */
    private static final float RING_FRAME_V = 32.0F / 2048.0F;
    /** 法阵半径（格），与旧实体版 PlayerRing.SIZE=3 一致 */
    private static final float RING_RADIUS = 1.5F;
    /** 法阵转一圈的 tick 数（与旧贴图动画 64 帧一致） */
    private static final float RING_PERIOD = 64.0F;

    /** 超阶施法圆环半径（格）：比常驻法阵略大，强调“放大招” */
    private static final float CAST_RING_RADIUS = 1.85F;
    /** 施法圆环转一圈的 tick 数（转得快一些，配合 2 秒的短时长） */
    private static final float CAST_RING_PERIOD = 14.0F;
    /** 施法圆环展开用时（tick）：从 35% 半径放大到满 */
    private static final float CAST_RING_GROW = 6.0F;
    /** 施法圆环末尾淡出用时（tick） */
    private static final float CAST_RING_FADE_OUT = 10.0F;

    // ---- 心灵系 / 治愈系 / 毒系圆圈：和超阶圆环一样画在玩家脚下，跟随玩家，客户端本地倒计时 ----
    /** 顺序必须与 MagicCirclePayload 的 PACIFY..PLANT_GRAFT 常量一致 */
    private static final Identifier[] CIRCLE_TEXTURES = {
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/mind_ring_white.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/mind_ring_blue.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/mind_ring_gray.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/mind_ring_red.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/mind_ring_gold.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/mind_ring_platinum.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/heal_ring_star.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/heal_ring_rebirth.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/poison_ring_mutation.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/poison_ring_gale.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/bless_ring_strength.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/bless_ring_hymn.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/bless_ring_spirit.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/bless_ring_seal.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/bless_ring_stars.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/sound_ring_dirge.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/sound_ring_finale.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/curse_ring_ghost.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/curse_ring_pact.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/plant_ring_prison.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/plant_ring_overgrowth.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/plant_ring_hand.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/plant_ring_graft.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/undead_ring_world.png"),
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "textures/item/space_ring_teleport.png")
    };
    /** 圆环贴图主环占 115/128 边长，面片要按这个比例放大，视觉边界才等于判定半径 */
    private static final float CIRCLE_TEXTURE_FILL = 115.0F / 128.0F;
    /** 展开用时（tick）：从 55% 半径放大到满 */
    private static final float CIRCLE_GROW = 8.0F;
    /** 末尾淡出用时（tick） */
    private static final float CIRCLE_FADE_OUT = 12.0F;
    /** 缓慢自转周期（tick） */
    private static final float CIRCLE_PERIOD = 120.0F;
    /** 瞬移圆环：要求「顺时针一圈正好 1 秒」，所以单独用 20 tick 的周期 */
    private static final float CIRCLE_PERIOD_TELEPORT = 20.0F;

    // 翅膀扇动：模型空间 -Z 是身前、+Z 是身后，绕 +Y 正转会把左翼尖推向身前（-Z）。
    // 需求：向前最多只超出背平面 5°，向后最多 ~80°（像鸟翼后撩再收回）。
    // 直接在角度空间做正弦（中心 -37.5°、半摆幅 42.5°）：前后扫过角度比例即 5:80，
    // 角速度全程平滑连续，不会出现“前半周期几乎不动”的停顿感。
    private static final float WING_FORWARD_DEG = 5.0F;
    private static final float WING_BACK_DEG = -80.0F;
    private static final float WING_CENTER =
            (float) Math.toRadians((WING_FORWARD_DEG + WING_BACK_DEG) * 0.5F);
    private static final float WING_HALF_RANGE =
            (float) Math.toRadians((WING_FORWARD_DEG - WING_BACK_DEG) * 0.5F);

    public PlayerFxLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                       AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible || state.isSpectator) {
            return;
        }
        Integer rawFlags = state.getRenderData(MagicClientFx.FLAGS_KEY);
        int flags = rawFlags == null ? 0 : rawFlags;

        // ---- 超阶圆环：脚底一层，系列色 + 内外发光；技能持续多久就亮多久，进入冷却才淡出 ----
        MagicClientFx.CastRingView castRing = state.getRenderData(MagicClientFx.CAST_RING_KEY);
        if (castRing != null && castRing.elementOrdinal() >= 0
                && castRing.elementOrdinal() < MagicElement.values().length) {
            MagicElement element = MagicElement.values()[castRing.elementOrdinal()];
            Identifier texture = Identifier.fromNamespaceAndPath(PlatformMods.MODID,
                    "textures/fx/cast_ring_" + element.id() + ".png");
            // 服务端补发会刷新剩余时间，elapsed 可能短暂为负，夹到 0 避免透明度过冲
            int elapsed = Math.max(0, castRing.durationTicks() - castRing.remainingTicks());
            float grow = Math.min(1.0F, (elapsed + 1.0F) / CAST_RING_GROW);
            float radius = CAST_RING_RADIUS * (0.35F + 0.65F * grow);
            float alpha = Math.min(1.0F, (elapsed + 1.0F) / 3.0F);
            if (castRing.remainingTicks() < CAST_RING_FADE_OUT) {
                alpha = Math.min(alpha, castRing.remainingTicks() / CAST_RING_FADE_OUT);
            }
            int alphaByte = Mth.clamp((int) (alpha * 255.0F), 0, 255);
            if (alphaByte > 3) {
                poseStack.pushPose();
                // 略低于常驻法阵（1.47），两层同时存在时不会共面闪烁
                poseStack.translate(0.0D, 1.45D, 0.0D);
                poseStack.mulPose(Axis.YP.rotation(
                        -state.ageInTicks * (float) (Math.PI * 2.0D) / CAST_RING_PERIOD));
                collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(texture),
                        (pose, buffer) -> ringQuad(pose, buffer, lightCoords, radius, 1.0F, alphaByte));
                poseStack.popPose();
            }
        }

        // ---- 心灵系圆圈/圆环：客户端贴身绘制，跟着玩家走，半径 = 技能判定范围 ----
        // 服务端只在开始/结束发状态包，这里读的是客户端本地倒计时，不生成任何实体。
        List<MagicClientFx.CircleView> circles = state.getRenderData(MagicClientFx.CIRCLES_KEY);
        if (circles != null && !circles.isEmpty()) {
            for (int i = 0; i < circles.size(); i++) {
                MagicClientFx.CircleView circle = circles.get(i);
                if (circle.variant() < 0 || circle.variant() >= CIRCLE_TEXTURES.length) {
                    continue;
                }
                boolean teleport = circle.variant() == MagicCirclePayload.SPACE_TELEPORT;
                int elapsed = Math.max(0, circle.durationTicks() - circle.remainingTicks());
                // 瞬移圆环只有 20 tick，若沿用 8 tick 展开 + 12 tick 淡出，整秒几乎看不见。
                // 这里让它一出现就是满半径、满亮度，转满一圈后由服务端 stop 包直接收掉。
                float grow = teleport ? 1.0F : Math.min(1.0F, (elapsed + 1.0F) / CIRCLE_GROW);
                float alpha = teleport ? 1.0F : Math.min(1.0F, (elapsed + 1.0F) / 4.0F);
                if (!teleport && circle.remainingTicks() < CIRCLE_FADE_OUT) {
                    alpha = Math.min(alpha, circle.remainingTicks() / CIRCLE_FADE_OUT);
                }
                int alphaByte = Mth.clamp((int) (alpha * 255.0F), 0, 255);
                if (alphaByte <= 3) {
                    continue;
                }
                // 面片半宽 = 判定半径 / 贴图填充比例；多个圈按索引错开一点高度防共面闪烁
                float quadRadius = circle.radius() / CIRCLE_TEXTURE_FILL * (0.55F + 0.45F * grow);
                poseStack.pushPose();
                poseStack.translate(0.0D, 1.46D - i * 0.006D, 0.0D);
                float period = teleport ? CIRCLE_PERIOD_TELEPORT : CIRCLE_PERIOD;
                poseStack.mulPose(Axis.YP.rotation(
                        state.ageInTicks * (float) (Math.PI * 2.0D) / period));
                Identifier texture = CIRCLE_TEXTURES[circle.variant()];
                collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(texture),
                        (pose, buffer) -> ringQuad(pose, buffer, lightCoords, quadRadius, 1.0F, alphaByte));
                poseStack.popPose();
            }
        }

        // ---- 脚下常驻法阵：不挂 body（弯腰/飞行时也保持水平），雷神优先于火神 ----
        Identifier ringTexture = (flags & MagicClientFx.THUNDER_GOD) != 0 ? THUNDER_RING_TEXTURE
                : (flags & MagicClientFx.FIRE_GOD) != 0 ? FIRE_RING_TEXTURE : null;
        if (ringTexture != null) {
            poseStack.pushPose();
            // 模型空间 +y 朝下，脚底平面在 y=1.5（24/16），抬高一点点防止和地面 z-fighting
            poseStack.translate(0.0D, 1.47D, 0.0D);
            poseStack.mulPose(Axis.YP.rotation(
                    state.ageInTicks * (float) (Math.PI * 2.0D) / RING_PERIOD));
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(ringTexture),
                    (pose, buffer) -> ringQuad(pose, buffer, lightCoords, RING_RADIUS, RING_FRAME_V, 255));
            poseStack.popPose();
        }

        // ---- 背后六翼：挂在 body 部件上，潜行前倾/飞行动作都跟着走 ----
        if ((flags & MagicClientFx.WIND_WINGS) != 0) {
            // 扇动曲线：以 -37.5° 为中心的正弦，满摆幅时在 +5°（前压）与 -80°（后撩）之间
            // 平滑往返；drive 随走动/滑翔放大摆幅（静止时是轻微悬停摆动）
            float drive = Math.min(1.0F,
                    0.32F + state.walkAnimationSpeed * 0.5F + state.fallFlyingScale() * 0.68F);
            float angle = WING_CENTER + Mth.sin(state.ageInTicks * 0.22F) * WING_HALF_RANGE * drive;

            poseStack.pushPose();
            this.getParentModel().body.translateAndRotate(poseStack);
            // 左翼（玩家左手侧）：z=0.30 明确落在身体(±0.125)和手臂(z±0.25)之后，
            // 扇动时翼面绕 Y 轴摆动也不会扫进模型里
            poseStack.pushPose();
            poseStack.translate(0.03D, 0.1D, 0.30D);
            poseStack.mulPose(Axis.YP.rotation(angle));
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WINGS_TEXTURE),
                    (pose, buffer) -> wingQuad(pose, buffer, true, lightCoords));
            poseStack.popPose();
            // 右翼（玩家右手侧）
            poseStack.pushPose();
            poseStack.translate(-0.03D, 0.1D, 0.30D);
            poseStack.mulPose(Axis.YP.rotation(-angle));
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WINGS_TEXTURE),
                    (pose, buffer) -> wingQuad(pose, buffer, false, lightCoords));
            poseStack.popPose();
            poseStack.popPose();
        }
    }

    // ==================================================================
    // 几何：全部双面绘制（不依赖渲染类型的剔除设置），单位是格
    // ==================================================================

    /**
     * 单片翅膀：从脊柱（x=0）向外展开 1.05 格，竖直 -0.55（头顶上方）到 1.45（脚踝）。
     * 贴图 64x64 左右各半：左翼取左半边、右翼取右半边，翼尖朝外。
     */
    private static void wingQuad(PoseStack.Pose pose, VertexConsumer buffer, boolean left, int light) {
        float tipX = left ? 1.05F : -1.05F;
        // 翼根在贴图中线、翼尖在贴图外缘
        float rootU = left ? 31.0F / 64.0F : 32.0F / 64.0F;
        float tipU = left ? 0.0F : 63.0F / 64.0F;
        float top = -0.55F;
        float bottom = 1.45F;
        // 正面（朝背后）
        vertex(buffer, pose, 0.0F, top, 0.0F, rootU, 0.0F, 0.0F, 0.0F, 1.0F, light, 255);
        vertex(buffer, pose, tipX, top, 0.0F, tipU, 0.0F, 0.0F, 0.0F, 1.0F, light, 255);
        vertex(buffer, pose, tipX, bottom, 0.0F, tipU, 1.0F, 0.0F, 0.0F, 1.0F, light, 255);
        vertex(buffer, pose, 0.0F, bottom, 0.0F, rootU, 1.0F, 0.0F, 0.0F, 1.0F, light, 255);
        // 背面（顶点顺序反转）
        vertex(buffer, pose, 0.0F, bottom, 0.0F, rootU, 1.0F, 0.0F, 0.0F, -1.0F, light, 255);
        vertex(buffer, pose, tipX, bottom, 0.0F, tipU, 1.0F, 0.0F, 0.0F, -1.0F, light, 255);
        vertex(buffer, pose, tipX, top, 0.0F, tipU, 0.0F, 0.0F, 0.0F, -1.0F, light, 255);
        vertex(buffer, pose, 0.0F, top, 0.0F, rootU, 0.0F, 0.0F, 0.0F, -1.0F, light, 255);
    }

    /**
     * 水平圆环面片（双面）。
     *
     * @param radius 半径（格）
     * @param vMax   贴图纵向取到多少（动画条只取第一帧时传 32/2048，普通整图传 1.0）
     * @param alpha  顶点透明度（0~255），用于淡出
     */
    private static void ringQuad(PoseStack.Pose pose, VertexConsumer buffer, int light,
                                 float radius, float vMax, int alpha) {
        float r = radius;
        float v = vMax;
        // 顶面（从上方看）
        vertex(buffer, pose, -r, 0.0F, -r, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, light, alpha);
        vertex(buffer, pose, r, 0.0F, -r, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, light, alpha);
        vertex(buffer, pose, r, 0.0F, r, 1.0F, v, 0.0F, 1.0F, 0.0F, light, alpha);
        vertex(buffer, pose, -r, 0.0F, r, 0.0F, v, 0.0F, 1.0F, 0.0F, light, alpha);
        // 底面（从下方看）
        vertex(buffer, pose, -r, 0.0F, r, 0.0F, v, 0.0F, -1.0F, 0.0F, light, alpha);
        vertex(buffer, pose, r, 0.0F, r, 1.0F, v, 0.0F, -1.0F, 0.0F, light, alpha);
        vertex(buffer, pose, r, 0.0F, -r, 1.0F, 0.0F, 0.0F, -1.0F, 0.0F, light, alpha);
        vertex(buffer, pose, -r, 0.0F, -r, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F, light, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light, int alpha) {
        buffer.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
