package com.cqgzs.cqlove.lan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.world.level.GameType;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 局域网界面配置项（需求文档 二.2）。
 *
 * <p>负责把界面上的配置元素保存到 JSON 文件 / 从 JSON 文件读取，
 * 对应界面上的“保存配置”按钮与“自动读取已储存配置”开关。</p>
 *
 * <p>文件位置：{@code .minecraft/config/cqlove/lan_share.json}</p>
 *
 * <p>各项默认值（需求文档）：</p>
 * <ul>
 *   <li>游戏模式：生存模式</li>
 *   <li>允许命令：关闭</li>
 *   <li>联机模式：在线模式</li>
 *   <li>允许PVP：关闭</li>
 *   <li>监听端口：25565</li>
 *   <li>玩家最大数：8</li>
 *   <li>自动读取已储存配置：开</li>
 * </ul>
 */
public final class LanSettings {

    /** 默认监听端口（需求文档 i 项） */
    public static final int DEFAULT_PORT = 25565;
    /** 默认玩家最大数（需求文档 j 项） */
    public static final int DEFAULT_MAX_PLAYERS = 8;
    /** 玩家最大数允许范围（防止误输入把服务器卡死） */
    public static final int MAX_PLAYERS_LIMIT = 1024;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** JSON 配置文件路径 */
    private static final Path CONFIG_PATH =
            FMLPaths.CONFIGDIR.get().resolve("cqlove").resolve("lan_share.json");

    // ------------------------------------------------------------------
    // 配置字段（与界面控件一一对应）
    // ------------------------------------------------------------------

    /** 游戏模式 id，见 {@link GameType#getName()}，默认生存 */
    public String gameMode = GameType.SURVIVAL.getName();

    /** 允许命令，默认关闭（需求文档 f 项） */
    public boolean allowCommands = false;

    /** 联机模式 id，见 {@link OnlineModeSetting#getId()}，默认在线模式 */
    public String onlineMode = OnlineModeSetting.ONLINE.getId();

    /** 允许PVP，默认关闭（需求文档 h 项） */
    public boolean pvpAllowed = false;

    /** 监听端口，默认 25565（需求文档 i 项） */
    public int port = DEFAULT_PORT;

    /** 玩家最大数，默认 8（需求文档 j 项） */
    public int maxPlayers = DEFAULT_MAX_PLAYERS;

    /** 打开界面时是否自动读取已储存配置，默认开（需求文档 b 项） */
    public boolean autoLoad = true;

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /** 配置文件是否已存在（用于“自动读取已储存配置”判断） */
    public static boolean configFileExists() {
        return Files.isRegularFile(CONFIG_PATH);
    }

    /**
     * 从 JSON 文件读取配置。
     * 文件不存在或解析失败时返回一份默认配置，保证界面始终可用。
     */
    public static LanSettings load() {
        if (configFileExists()) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                LanSettings loaded = GSON.fromJson(reader, LanSettings.class);
                if (loaded != null) {
                    loaded.sanitize();
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                // 解析失败不阻断游戏，记录后使用默认值
                com.cqgzs.cqlove.PlatformMods.LOGGER.warn("[cqlove] 读取局域网配置失败，使用默认值: {}", CONFIG_PATH, e);
            }
        }
        return new LanSettings();
    }

    /** 将当前配置写入 JSON 文件（“保存配置”按钮调用） */
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            com.cqgzs.cqlove.PlatformMods.LOGGER.warn("[cqlove] 保存局域网配置失败: {}", CONFIG_PATH, e);
        }
    }

    /** 数据合法性校正：端口/人数越界时夹取到合法范围 */
    public void sanitize() {
        if (port < 1024 || port > 65535) {
            port = DEFAULT_PORT;
        }
        if (maxPlayers < 1 || maxPlayers > MAX_PLAYERS_LIMIT) {
            maxPlayers = DEFAULT_MAX_PLAYERS;
        }
    }

    /** 读取游戏模式枚举（无法识别时回退生存模式） */
    public GameType gameType() {
        GameType type = GameType.byName(gameMode);
        return type == null ? GameType.SURVIVAL : type;
    }

    /** 读取联机模式枚举（无法识别时回退在线模式） */
    public OnlineModeSetting onlineModeSetting() {
        return OnlineModeSetting.byId(onlineMode);
    }
}
