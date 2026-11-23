package com.cqgzs.cqlove.magic;

import java.util.function.Supplier;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 玩家魔法数据的 Attachment 注册与访问入口。
 *
 * <p>选择 Attachment 而不是自己存文件的原因：它天然是“每个玩家一份”，
 * 随玩家存档一起读写，多人服务器每个玩家互不干扰（需求：每个玩家单独储存）。</p>
 *
 * <p>{@code copyOnDeath()} 保证死亡重生后魔法数据（已觉醒系列/技能/天赋）不丢。</p>
 */
public final class MagicData {

    /** 附件类型注册器，在 Main 类构造时挂到 mod 事件总线 */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlatformMods.MODID);

    /** 玩家魔法数据附件：cqlove:player_magic */
    public static final Supplier<AttachmentType<PlayerMagicData>> PLAYER_MAGIC =
            ATTACHMENTS.register("player_magic",
                    () -> AttachmentType.serializable(PlayerMagicData::new).copyOnDeath().build());

    private MagicData() {
    }

    /** 取玩家魔法数据（不存在时自动创建一份默认数据） */
    public static PlayerMagicData get(Player player) {
        return player.getData(PLAYER_MAGIC);
    }
}
