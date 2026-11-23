package com.cqgzs.cqlove.lan;

/**
 * 局域网运行时状态（不持久化）。
 *
 * <p>“创建局域网”时由界面写入，Mixin 读取：</p>
 * <ul>
 *   <li>{@code uuidFixEnabled} —— 是否启用 UUID 修复（离线模式），
 *       被 {@code UUIDUtilMixin} 在玩家登录握手时读取。</li>
 *   <li>{@code lanMaxPlayers} —— 玩家最大数，
 *       被 {@code IntegratedServerMixin#getMaxPlayers} 读取（原版硬编码 8）。</li>
 * </ul>
 *
 * <p>使用 volatile 保证网络线程/服务器线程与客户端界面线程之间的可见性。</p>
 */
public final class LanRuntime {

    /** 是否对离线登录的玩家做 UUID 修复（联机模式 = UUID修复+离线模式 时为 true） */
    private static volatile boolean uuidFixEnabled = false;

    /** 当前局域网允许的最大玩家数（默认值与原版一致 = 8） */
    private static volatile int lanMaxPlayers = LanSettings.DEFAULT_MAX_PLAYERS;

    private LanRuntime() {
    }

    public static boolean isUuidFixEnabled() {
        return uuidFixEnabled;
    }

    public static void setUuidFixEnabled(boolean enabled) {
        uuidFixEnabled = enabled;
    }

    public static int getLanMaxPlayers() {
        return lanMaxPlayers;
    }

    public static void setLanMaxPlayers(int maxPlayers) {
        // 防御性夹取，避免异常值导致服务器行为不可控
        if (maxPlayers < 1) {
            maxPlayers = 1;
        }
        if (maxPlayers > LanSettings.MAX_PLAYERS_LIMIT) {
            maxPlayers = LanSettings.MAX_PLAYERS_LIMIT;
        }
        lanMaxPlayers = maxPlayers;
    }

    /** 世界关闭时复位，避免影响下一次开局的默认行为 */
    public static void reset() {
        uuidFixEnabled = false;
        lanMaxPlayers = LanSettings.DEFAULT_MAX_PLAYERS;
    }
}
