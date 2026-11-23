package com.cqgzs.cqlove.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display.TextDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 让客户端能直接给 TextDisplay 设置文本。
 *
 * <p>26.1.2 的 {@code TextDisplay.readAdditionalSaveData} 在客户端分支里会把 text 强制清空
 * （只有服务端才有命令上下文做文本解析），所以虚拟特效走 {@code entity.load(NBT)} 写文本时
 * 永远得到空文本 —— 伤害数字完全看不见。这里用 mixin invoker 暴露 private 的 setText，
 * 客户端建完实体后直接调用它写文本，绕开 NBT 的客户端清空分支。</p>
 */
@Mixin(TextDisplay.class)
public interface TextDisplayClientMixin {

    @Invoker("setText")
    void cqlove$setTextPublic(Component text);
}
