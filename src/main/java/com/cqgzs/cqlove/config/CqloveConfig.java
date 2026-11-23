package com.cqgzs.cqlove.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Mod 总配置框架（需求文档 二.1）。
 *
 * <p>每个大功能一个独立开关，各功能初始化/事件处理前先读取对应开关：</p>
 * <ul>
 *   <li>{@link #LAN_ENABLED}   联机功能（自定义局域网界面），默认开启</li>
 *   <li>{@link #MAP_ENABLED}   地图功能（区块预加载），默认开启</li>
 *   <li>{@link #MAGIC_ENABLED} 魔法世界，默认开启</li>
 * </ul>
 *
 * <p>配置文件路径：.minecraft/config/cqlove-common.toml</p>
 */
public final class CqloveConfig {

    /** 联机功能开关（默认开） */
    public static final ModConfigSpec.BooleanValue LAN_ENABLED;
    /** 地图功能开关（默认开） */
    public static final ModConfigSpec.BooleanValue MAP_ENABLED;
    /** 魔法世界开关（默认开） */
    public static final ModConfigSpec.BooleanValue MAGIC_ENABLED;

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("cqlove 功能总开关 / Feature toggles").push("features");

        LAN_ENABLED = builder
                .comment("联机功能：替换原版“对局域网开放”界面（默认开启）")
                .define("lanEnabled", true);

        MAP_ENABLED = builder
                .comment("地图功能：区块预加载与进度条（默认开启）")
                .define("mapEnabled", true);

        MAGIC_ENABLED = builder
                .comment("魔法世界：天赋/技能/觉醒体系（默认开启）")
                .define("magicEnabled", true);

        builder.pop();
        SPEC = builder.build();
    }

    private CqloveConfig() {
    }

    /** 在主 Mod 类构造时调用，注册配置文件 */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "cqlove-common.toml");
    }

    /** 联机功能是否启用（事件处理中调用，未加载完成时按默认 true 处理） */
    public static boolean lanEnabled() {
        return LAN_ENABLED.getAsBoolean();
    }

    /** 地图功能是否启用 */
    public static boolean mapEnabled() {
        return MAP_ENABLED.getAsBoolean();
    }

    /** 魔法世界是否启用 */
    public static boolean magicEnabled() {
        return MAGIC_ENABLED.getAsBoolean();
    }
}
