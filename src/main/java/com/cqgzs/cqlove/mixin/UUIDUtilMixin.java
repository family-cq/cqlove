package com.cqgzs.cqlove.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.UUIDUtil;

import com.cqgzs.cqlove.lan.LanRuntime;
import com.cqgzs.cqlove.lan.UuidFixer;

/**
 * UUID 修复（需求文档 二.2.g “UUID修复+离线模式”）。
 *
 * <p>离线模式下原版通过 {@link UUIDUtil#createOfflinePlayerUUID(String)}
 * 按用户名生成离线 UUID。本 Mixin 仅在用户选择“UUID修复+离线模式”
 * （{@link LanRuntime#isUuidFixEnabled()}）时介入：先尝试查询 Mojang
 * 官方 UUID，查到则直接使用；查不到返回 null，走原版离线 UUID 逻辑。</p>
 *
 * <p>参考实现：LanServerProperties（MIT, rikka0w0）的 MixinUUIDUtil。</p>
 */
@Mixin(UUIDUtil.class)
public abstract class UUIDUtilMixin {

    @Inject(method = "createOfflinePlayerUUID", at = @At("HEAD"), cancellable = true)
    private static void cqlove$fixOfflineUuid(String playerName, CallbackInfoReturnable<UUID> cir) {
        // 只在“UUID修复+离线模式”开启时介入，其余情况完全走原版逻辑
        if (!LanRuntime.isUuidFixEnabled()) {
            return;
        }
        UUID official = UuidFixer.getOfficialUuid(playerName);
        if (official != null) {
            cir.setReturnValue(official);
        }
        // 返回 null：不取消，继续执行原版离线 UUID 生成
    }
}
