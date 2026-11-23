package com.cqgzs.cqlove.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.server.IntegratedServer;

import com.cqgzs.cqlove.lan.LanRuntime;

/**
 * 修改原版“玩家最大数”硬编码（需求文档 二.2.j）。
 *
 * <p>原版 {@link IntegratedServer#getMaxPlayers()} 固定返回 8，
 * 这里改为读取用户在自定义局域网界面上设置并保存的值
 * （默认仍为 8，与原版行为一致）。</p>
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    /**
     * HEAD 处直接替换返回值：原版方法就是 {@code return 8;}，无其他逻辑，
     * 用我们的配置值替换不会产生副作用。
     */
    @Inject(method = "getMaxPlayers", at = @At("HEAD"), cancellable = true)
    private void cqlove$overrideMaxPlayers(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(LanRuntime.getLanMaxPlayers());
    }
}
