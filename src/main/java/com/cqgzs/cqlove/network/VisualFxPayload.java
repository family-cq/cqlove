package com.cqgzs.cqlove.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 服务端 -> 客户端：<b>一个「虚拟特效实体」的生成 / 变换 / 销毁</b>。
 *
 * <p>魔法特效（法阵、冰墙、旋风、植物、水墙、伤害数字……）以前是服务端真的生成
 * {@code Display.ItemDisplay} / {@code Display.BlockDisplay} / {@code Display.TextDisplay} 实体，
 * 客户端只是被动同步。那样每个特效都要占服务端实体槽、参与区块实体管理、每 tick 走实体同步。</p>
 *
 * <p>现在改成：<b>服务端只描述「这里要出现一个什么样子的特效」，用这个包发给附近玩家；
 * 真正的 Display 实体由客户端自己生成、自己驱动渲染</b>。
 * 服务端零实体、零渲染开销，客户端拿到的位置永远是插值后的结果，跟随玩家也不会拖尾。</p>
 *
 * <p>一个特效在服务端侧由 {@code FxHandle} 代表（只有 id 和状态，不是真实体），
 * 客户端用同一 id 复用一个 Display 实体，因此 update 包只带变化的变换，不带模型。</p>
 */
public record VisualFxPayload(
        int id,
        int action,
        int kind,
        double x, double y, double z,
        float scaleX, float scaleY, float scaleZ,
        float rotX, float rotY, float rotZ, float rotW,
        int lifeTicks,
        int interpolationTicks,
        int billboard,
        int background,
        boolean glow,
        ItemStack item,
        BlockState block,
        Component text) implements CustomPacketPayload {

    /** 新建：完整描述（模型 + 变换 + 生命周期） */
    public static final int ACTION_SPAWN = 0;
    /** 变换：只带位置 / 缩放 / 旋转 / 续期 */
    public static final int ACTION_UPDATE = 1;
    /** 销毁 */
    public static final int ACTION_REMOVE = 2;

    /** 贴图面片（ItemDisplay，魔法图片） */
    public static final int KIND_BILLBOARD = 0;
    /** 真实方块模型（BlockDisplay，植物系 / 岩浆块） */
    public static final int KIND_BLOCK = 1;
    /** 文本（TextDisplay，伤害数字） */
    public static final int KIND_TEXT = 2;

    public static final Type<VisualFxPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cqlove", "visual_fx"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VisualFxPayload> CODEC =
            StreamCodec.of(VisualFxPayload::encode, VisualFxPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, VisualFxPayload payload) {
        buf.writeVarInt(payload.id);
        buf.writeByte(payload.action);
        buf.writeByte(payload.kind);
        buf.writeDouble(payload.x);
        buf.writeDouble(payload.y);
        buf.writeDouble(payload.z);
        buf.writeFloat(payload.scaleX);
        buf.writeFloat(payload.scaleY);
        buf.writeFloat(payload.scaleZ);
        buf.writeFloat(payload.rotX);
        buf.writeFloat(payload.rotY);
        buf.writeFloat(payload.rotZ);
        buf.writeFloat(payload.rotW);
        buf.writeVarInt(payload.lifeTicks);
        buf.writeVarInt(payload.interpolationTicks);
        if (payload.action == ACTION_SPAWN) {
            buf.writeByte(payload.billboard);
            buf.writeInt(payload.background);
            buf.writeBoolean(payload.glow);
            switch (payload.kind) {
                case KIND_BILLBOARD -> ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.item);
                case KIND_BLOCK -> buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(payload.block));
                case KIND_TEXT -> ComponentSerialization.STREAM_CODEC.encode(buf, payload.text());
                default -> {
                }
            }
        }
    }

    private static VisualFxPayload decode(RegistryFriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int action = buf.readByte();
        int kind = buf.readByte();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float scaleX = buf.readFloat();
        float scaleY = buf.readFloat();
        float scaleZ = buf.readFloat();
        float rotX = buf.readFloat();
        float rotY = buf.readFloat();
        float rotZ = buf.readFloat();
        float rotW = buf.readFloat();
        int lifeTicks = buf.readVarInt();
        int interpolationTicks = buf.readVarInt();
        int billboard = 0;
        int background = 0;
        boolean glow = false;
        ItemStack item = ItemStack.EMPTY;
        BlockState block = Blocks.AIR.defaultBlockState();
        Component text = Component.empty();
        if (action == ACTION_SPAWN) {
            billboard = buf.readByte();
            background = buf.readInt();
            glow = buf.readBoolean();
            switch (kind) {
                case KIND_BILLBOARD -> item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                case KIND_BLOCK -> {
                    BlockState state = Block.BLOCK_STATE_REGISTRY.byId(buf.readVarInt());
                    if (state != null) {
                        block = state;
                    }
                }
                case KIND_TEXT -> text = ComponentSerialization.STREAM_CODEC.decode(buf);
                default -> {
                }
            }
        }
        return new VisualFxPayload(id, action, kind, x, y, z,
                scaleX, scaleY, scaleZ, rotX, rotY, rotZ, rotW,
                lifeTicks, interpolationTicks, billboard, background, glow, item, block, text);
    }

    /** 生成包：贴图面片 */
    public static VisualFxPayload spawnBillboard(int id, double x, double y, double z,
                                                 float sx, float sy, float sz,
                                                 float qx, float qy, float qz, float qw,
                                                 int lifeTicks, int interpolationTicks,
                                                 int billboard, boolean glow, ItemStack item) {
        return new VisualFxPayload(id, ACTION_SPAWN, KIND_BILLBOARD, x, y, z,
                sx, sy, sz, qx, qy, qz, qw, lifeTicks, interpolationTicks, billboard, 0, glow,
                item, Blocks.AIR.defaultBlockState(), Component.empty());
    }

    /** 生成包：真实方块 */
    public static VisualFxPayload spawnBlock(int id, double x, double y, double z,
                                             float sx, float sy, float sz,
                                             float qx, float qy, float qz, float qw,
                                             int lifeTicks, int interpolationTicks,
                                             boolean glow, BlockState state) {
        return new VisualFxPayload(id, ACTION_SPAWN, KIND_BLOCK, x, y, z,
                sx, sy, sz, qx, qy, qz, qw, lifeTicks, interpolationTicks, 0, 0, glow,
                ItemStack.EMPTY, state, Component.empty());
    }

    /** 生成包：文本（伤害数字） */
    public static VisualFxPayload spawnText(int id, double x, double y, double z,
                                            float sx, float sy, float sz,
                                            int lifeTicks, int interpolationTicks,
                                            int billboard, int background, Component text) {
        return new VisualFxPayload(id, ACTION_SPAWN, KIND_TEXT, x, y, z,
                sx, sy, sz, 0.0F, 0.0F, 0.0F, 1.0F, lifeTicks, interpolationTicks,
                billboard, background, false, ItemStack.EMPTY,
                Blocks.AIR.defaultBlockState(), text);
    }

    /** 变换包 */
    public static VisualFxPayload update(int id, int kind, double x, double y, double z,
                                         float sx, float sy, float sz,
                                         float qx, float qy, float qz, float qw,
                                         int lifeTicks, int interpolationTicks) {
        return new VisualFxPayload(id, ACTION_UPDATE, kind, x, y, z,
                sx, sy, sz, qx, qy, qz, qw, lifeTicks, interpolationTicks, 0, 0, false,
                ItemStack.EMPTY, Blocks.AIR.defaultBlockState(), Component.empty());
    }

    /** 销毁包 */
    public static VisualFxPayload remove(int id, int kind) {
        return new VisualFxPayload(id, ACTION_REMOVE, kind, 0.0D, 0.0D, 0.0D,
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0, 0, 0, 0, false,
                ItemStack.EMPTY, Blocks.AIR.defaultBlockState(), Component.empty());
    }

    /** 兼容：BlockPos 便捷重载（部分技能直接用方块坐标） */
    public static VisualFxPayload spawnBlock(int id, BlockPos pos,
                                             float sx, float sy, float sz,
                                             float qx, float qy, float qz, float qw,
                                             int lifeTicks, int interpolationTicks,
                                             boolean glow, BlockState state) {
        return spawnBlock(id, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                sx, sy, sz, qx, qy, qz, qw, lifeTicks, interpolationTicks, glow, state);
    }
}
