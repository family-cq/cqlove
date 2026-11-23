package com.cqgzs.cqlove.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 魔法世界专属创造模式物品栏 Tab（需求：四本书与后续物品统一放这里）。
 *
 * <p>Tab 图标用模组徽记（贴图来自存档的 icon_32x32.png，
 * 注册物品 {@code cqlove:mod_icon} 承载贴图，因为 Tab 图标必须是 ItemStack）。</p>
 */
public final class MagicTab {

    /** Tab 注册器 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PlatformMods.MODID);

    /** 主 Tab：名称走语言文件 itemGroup.cqlove */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cqlove"))
                    .icon(() -> new ItemStack(MagicItems.MOD_ICON.get()))
                    .displayItems((parameters, output) -> {
                        // 四本魔法书
                        output.accept(MagicItems.AWAKEN_BOOK.get());
                        output.accept(MagicItems.TALENT_BOOK.get());
                        output.accept(MagicItems.RANK_BOOK.get());
                        output.accept(MagicItems.BODY_BOOK.get());
                        // 蓝量瓶
                        output.accept(MagicItems.MANA_BOTTLE.get());
                        // 徽记也放进来，方便辨认 Tab 图标来源
                        output.accept(MagicItems.MOD_ICON.get());
                        // 后续：法杖 / 法套装注册后在这里追加 output.accept(...)
                    })
                    .build());

    private MagicTab() {
    }

    /** 主类构造时调用 */
    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
