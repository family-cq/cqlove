package com.cqgzs.cqlove.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.mojang.math.Transformation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Brightness;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.network.VisualFxPayload;

/**
 * 客户端「虚拟特效实体」管理器。
 *
 * <p>服务端不再生成任何 Display 实体，只发 {@link VisualFxPayload}。
 * 这里收到包之后，在<b>客户端世界</b>里自己 new 一个 {@code Display.ItemDisplay} /
 * {@code Display.BlockDisplay}，交给原版渲染器绘制 —— 所以画面与以前完全一致，
 * 但服务端零实体、零渲染负担。</p>
 *
 * <p>几个关键处理：</p>
 * <ul>
 *   <li><b>id 取负</b>：客户端自造实体必须避开服务端分配的实体 id，
 *       否则 {@code ClientLevel.addEntity} 会把同 id 的真实体顶掉（玩家/怪物直接消失）。
 *       这里统一映射成 {@code -1 - packetId}。</li>
 *   <li><b>移动插值</b>：位置变化走 {@code InterpolationHandler.interpolateTo}，
 *       跟玩家跑动时特效贴着人不抖；缩放/朝向变化才重灌 NBT。</li>
 *   <li><b>兜底回收</b>：包里的 {@code lifeTicks} 到期自动移除，
 *       即使 remove 包丢了也不会永久残留。</li>
 * </ul>
 */
public final class ClientVisualFx {

    /** 包 id -> 客户端实体 */
    private static final Map<Integer, Entry> ACTIVE = new HashMap<>();

    /** 当前绑定客户端世界：换维度/重进世界时整体清空 */
    private static ClientLevel boundLevel;

    private static final class Entry {
        final Entity entity;
        final int kind;
        float scaleX;
        float scaleY;
        float scaleZ;
        float rotX;
        float rotY;
        float rotZ;
        float rotW;
        long expireGameTime;
        /** 模型信息（update 包只带变换，重建 NBT 时必须用这份缓存，否则贴图会被清空） */
        VisualFxPayload model;

        Entry(Entity entity, int kind, VisualFxPayload model) {
            this.entity = entity;
            this.kind = kind;
            this.model = model;
        }
    }

    private ClientVisualFx() {
    }

    /** 处理服务端下发的特效包（客户端主线程） */
    public static void accept(VisualFxPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        if (level != boundLevel) {
            clear();
            boundLevel = level;
        }
        switch (payload.action()) {
            case VisualFxPayload.ACTION_SPAWN -> spawn(level, payload);
            case VisualFxPayload.ACTION_UPDATE -> update(level, payload);
            case VisualFxPayload.ACTION_REMOVE -> remove(payload.id());
            default -> {
            }
        }
    }

    private static void spawn(ClientLevel level, VisualFxPayload payload) {
        Entry existing = ACTIVE.get(payload.id());
        if (existing != null) {
            // 重复的 spawn（keepalive 重发 / reshape 换了贴图）：刷新模型缓存后重建变换。
            // 位置用插值而不是硬切，否则每 40 tick 的补发会让跟随型特效抖一下。
            existing.model = payload;
            existing.expireGameTime = level.getGameTime() + payload.lifeTicks();
            rewriteTag(level, existing.entity, payload, payload);
            // TEXT 重发时 load NBT 又会把客户端文本清空，同样用 invoker 补回
            if (payload.kind() == VisualFxPayload.KIND_TEXT
                    && existing.entity instanceof Display.TextDisplay textDisplay) {
                ((com.cqgzs.cqlove.mixin.TextDisplayClientMixin) textDisplay)
                        .cqlove$setTextPublic(payload.text());
            }
            existing.entity.getInterpolation().interpolateTo(
                    new Vec3(payload.x(), payload.y(), payload.z()), 0.0F, 0.0F);
            rememberTransform(existing, payload);
            return;
        }
        Entity entity = switch (payload.kind()) {
            case VisualFxPayload.KIND_BLOCK -> createBlock(level, payload);
            case VisualFxPayload.KIND_TEXT -> createText(level, payload);
            default -> createBillboard(level, payload);
        };
        if (entity == null) {
            return;
        }
        // 关键：负 id，绝不与服务端同步下来的实体 id 冲突
        entity.setId(-1 - payload.id());
        entity.setPos(payload.x(), payload.y(), payload.z());
        level.addEntity(entity);

        Entry entry = new Entry(entity, payload.kind(), payload);
        entry.expireGameTime = level.getGameTime() + payload.lifeTicks();
        rememberTransform(entry, payload);
        ACTIVE.put(payload.id(), entry);
    }

    private static void update(ClientLevel level, VisualFxPayload payload) {
        Entry entry = ACTIVE.get(payload.id());
        if (entry == null || entry.entity.isRemoved()) {
            return;
        }
        boolean geometryChanged = !sameTransform(entry, payload);
        applyTransform(level, entry, payload, geometryChanged);
    }

    private static void applyTransform(ClientLevel level, Entry entry, VisualFxPayload payload,
                                       boolean rebuildGeometry) {
        entry.expireGameTime = level.getGameTime() + payload.lifeTicks();
        if (rebuildGeometry) {
            // 形变必须重灌 NBT（会顺带把位置写回旧值），灌完再落到新位置
            rewriteTag(level, entry.entity, payload, entry.model);
            if (entry.kind == VisualFxPayload.KIND_TEXT
                    && entry.entity instanceof Display.TextDisplay textDisplay) {
                ((com.cqgzs.cqlove.mixin.TextDisplayClientMixin) textDisplay)
                        .cqlove$setTextPublic(entry.model.text());
            }
            entry.entity.setPos(payload.x(), payload.y(), payload.z());
            entry.entity.setOldPosAndRot();
            rememberTransform(entry, payload);
        } else {
            // 纯位移：交给 Display 自带插值，跟玩家跑动时不会一卡一卡
            entry.entity.getInterpolation().interpolateTo(
                    new Vec3(payload.x(), payload.y(), payload.z()), 0.0F, 0.0F);
        }
    }

    private static void remove(int id) {
        Entry entry = ACTIVE.remove(id);
        if (entry != null) {
            entry.entity.discard();
        }
    }

    /** 每客户端 tick：清掉过期特效（服务端崩溃 / remove 丢包的兜底） */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            if (!ACTIVE.isEmpty()) {
                clear();
            }
            boundLevel = null;
            return;
        }
        if (level != boundLevel) {
            clear();
            boundLevel = level;
            return;
        }
        if (ACTIVE.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        var iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.expireGameTime <= now || entry.entity.isRemoved()) {
                entry.entity.discard();
                iterator.remove();
            }
        }
    }

    /** 退出世界 / 换维度：清空全部客户端特效实体 */
    public static void clear() {
        for (Entry entry : ACTIVE.values()) {
            entry.entity.discard();
        }
        ACTIVE.clear();
        boundLevel = null;
    }

    @Nullable
    private static Entity createBillboard(ClientLevel level, VisualFxPayload payload) {
        Display.ItemDisplay display = EntityType.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
        if (display == null) {
            return null;
        }
        rewriteTag(level, display, payload, payload);
        return display;
    }

    @Nullable
    private static Entity createBlock(ClientLevel level, VisualFxPayload payload) {
        Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
        if (display == null) {
            return null;
        }
        rewriteTag(level, display, payload, payload);
        return display;
    }

    @Nullable
    private static Entity createText(ClientLevel level, VisualFxPayload payload) {
        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
        if (display == null) {
            return null;
        }
        rewriteTag(level, display, payload, payload);
        // 关键：26.1.2 的 TextDisplay.readAdditionalSaveData 在客户端会把 text 清空，
        // 上面 rewriteTag 写的 NBT text 被吃掉了。用 mixin invoker 直接设文本补回来。
        ((com.cqgzs.cqlove.mixin.TextDisplayClientMixin) display).cqlove$setTextPublic(payload.text());
        return display;
    }

    /**
     * 用 NBT 重写 Display 的模型与变换。
     *
     * <p>26.1.2 里 {@code setTransformation} / {@code setBlockState} 都是私有，
     * 只能走 {@code load(TagValueInput)} 这条公开路径。注意三个坑：</p>
     * <ul>
     *   <li>{@code ItemDisplay} 在缺少 {@code item} 键时会清成空栈 → 必须原样写回；</li>
     *   <li>{@code Entity.load} 对缺失的 Pos / Motion / Rotation 按 0 处理 → 必须写回当前位置；</li>
     *   <li>{@code interpolation_duration} 决定形变过渡时长，0 为瞬间。</li>
     * </ul>
     */
    private static void rewriteTag(ClientLevel level, Entity entity, VisualFxPayload payload,
                                   VisualFxPayload model) {
        Vec3 pos = entity.position();
        float yRot = entity.getYRot();
        float xRot = entity.getXRot();

        Quaternionf rotation = new Quaternionf(payload.rotX(), payload.rotY(),
                payload.rotZ(), payload.rotW());
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                level.registryAccess());
        output.store("transformation", Transformation.CODEC, new Transformation(
                new Vector3f(), rotation,
                new Vector3f(payload.scaleX(), payload.scaleY(), payload.scaleZ()),
                new Quaternionf()));
        output.putInt("teleport_duration", 1);
        output.putInt("interpolation_duration", Math.max(0, payload.interpolationTicks()));
        output.putInt("start_interpolation", 0);
        output.store("brightness", Brightness.CODEC, new Brightness(15, 15));
        output.putFloat("view_range", 4.0F);
        output.store("Pos", com.mojang.serialization.Codec.DOUBLE.listOf(),
                List.of(pos.x, pos.y, pos.z));
        output.store("Motion", com.mojang.serialization.Codec.DOUBLE.listOf(),
                List.of(0.0D, 0.0D, 0.0D));
        output.store("Rotation", com.mojang.serialization.Codec.FLOAT.listOf(),
                List.of(yRot, xRot));

        switch (model.kind()) {
            case VisualFxPayload.KIND_BLOCK -> output.store("block_state", BlockState.CODEC, model.block());
            case VisualFxPayload.KIND_TEXT -> {
                output.store("text", net.minecraft.network.chat.ComponentSerialization.CODEC, model.text());
                output.store("billboard", Display.BillboardConstraints.CODEC,
                        billboardOf(model.billboard()));
                output.putInt("background", model.background());
                // 明确写满不透明度：-1(byte) = 255，避免缺省值在不同版本被当成透明导致看不见
                output.putByte("text_opacity", (byte) -1);
                // 关键：TextDisplay 渲染时按 line_width 把文本整体左移 (1 - width/2) 像素。
                // 默认 line_width=200 会让数字飘到实体左边约 2.5 格、看着"看不见"。
                // 这里按文本实际长度设置，数字就居中在实体位置正上方。
                String plain = model.text().getString();
                int lineWidth = Math.max(16, Math.min(160, plain.length() * 6 + 8));
                output.putInt("line_width", lineWidth);
                // 穿透遮挡显示：数字被方块/生物挡住时依然可见
                output.putBoolean("see_through", true);
            }
            default -> {
                ItemStack stack = model.item();
                output.store("item", ItemStack.CODEC, stack.isEmpty() ? ItemStack.EMPTY : stack);
                output.store("billboard", Display.BillboardConstraints.CODEC,
                        billboardOf(model.billboard()));
            }
        }

        CompoundTag tag = output.buildResult();
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));

        entity.setPos(pos.x, pos.y, pos.z);
        entity.setYRot(yRot);
        entity.setXRot(xRot);
        entity.setOldPosAndRot();
    }

    private static Display.BillboardConstraints billboardOf(int ordinal) {
        Display.BillboardConstraints[] values = Display.BillboardConstraints.values();
        if (ordinal < 0 || ordinal >= values.length) {
            return Display.BillboardConstraints.FIXED;
        }
        return values[ordinal];
    }

    private static void rememberTransform(Entry entry, VisualFxPayload payload) {
        entry.scaleX = payload.scaleX();
        entry.scaleY = payload.scaleY();
        entry.scaleZ = payload.scaleZ();
        entry.rotX = payload.rotX();
        entry.rotY = payload.rotY();
        entry.rotZ = payload.rotZ();
        entry.rotW = payload.rotW();
    }

    private static boolean sameTransform(Entry entry, VisualFxPayload payload) {
        return entry.kind == payload.kind()
                && Math.abs(entry.scaleX - payload.scaleX()) < 1.0E-4F
                && Math.abs(entry.scaleY - payload.scaleY()) < 1.0E-4F
                && Math.abs(entry.scaleZ - payload.scaleZ()) < 1.0E-4F
                && Math.abs(entry.rotX - payload.rotX()) < 1.0E-4F
                && Math.abs(entry.rotY - payload.rotY()) < 1.0E-4F
                && Math.abs(entry.rotZ - payload.rotZ()) < 1.0E-4F
                && Math.abs(entry.rotW - payload.rotW()) < 1.0E-4F;
    }
}
