package com.cqgzs.cqlove.lan;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.cqgzs.cqlove.PlatformMods;

/**
 * UUID 修复器（需求文档 二.2.g “UUID修复+离线模式”）。
 *
 * <p>原理：离线模式下原版用 {@code UUID.nameUUIDFromBytes("OfflinePlayer:"+name)}
 * 生成 UUID，导致同一玩家更换正版/离线登录方式后 UUID 不一致（背包、领地等数据丢失）。
 * 修复方式是在玩家离线登录时，先向 Mojang 官方接口查询该用户名的正版 UUID，
 * 查到则使用正版 UUID，查不到（非正版用户名/网络失败）则回退原版离线 UUID。</p>
 *
 * <p>参考实现：LanServerProperties（MIT, rikka0w0）。</p>
 */
public final class UuidFixer {

    /** Mojang 官方“用户名 -> 正版档案”接口 */
    private static final String MOJANG_PROFILE_API = "https://api.mojang.com/users/profiles/minecraft/";

    /** 连接/读取超时（毫秒），避免 Mojang 不可达时卡死登录流程 */
    private static final int TIMEOUT_MS = 3000;

    /** 查询结果缓存：同一个名字在一次开服期间只查一次，避免每次登录都访问外网 */
    private static final Map<String, UUID> CACHE = new ConcurrentHashMap<>();

    private UuidFixer() {
    }

    /**
     * 尝试获取该用户名的 Mojang 官方 UUID。
     *
     * @param playerName 玩家用户名
     * @return 官方 UUID；查询失败（非正版名/网络问题）返回 null，调用方回退原版逻辑
     */
    public static @Nullable UUID getOfficialUuid(String playerName) {
        // 命中缓存直接返回（含“查不到”的负缓存由调用方在缓存外处理）
        UUID cached = CACHE.get(playerName);
        if (cached != null) {
            return cached;
        }

        HttpURLConnection connection = null;
        try {
            URL url = URI.create(MOJANG_PROFILE_API + playerName).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                // 204/404 等：该名字没有正版档案，属于正常情况
                return null;
            }

            String body;
            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 返回格式：{"name":"xxx","id":"069a79f444e94726a5befca90e38aaf5"}
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("id")) {
                return null;
            }

            String dashedId = insertDashes(json.get("id").getAsString());
            UUID uuid = UUID.fromString(dashedId);
            CACHE.put(playerName, uuid);
            PlatformMods.LOGGER.info("[cqlove] UUID修复: 玩家 {} 使用官方 UUID {}", playerName, uuid);
            return uuid;
        } catch (IOException | RuntimeException e) {
            // 网络不可达/返回格式异常：静默回退原版离线 UUID
            PlatformMods.LOGGER.warn("[cqlove] UUID修复: 查询 {} 的官方 UUID 失败，回退离线 UUID ({})",
                    playerName, e.toString());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 把 Mojang 返回的 32 位无横线十六进制 id 补成标准 UUID 格式 */
    private static String insertDashes(String raw) {
        if (raw == null || raw.length() != 32) {
            throw new IllegalArgumentException("非法的 Mojang UUID: " + raw);
        }
        return raw.substring(0, 8) + "-"
                + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-"
                + raw.substring(16, 20) + "-"
                + raw.substring(20, 32);
    }
}
