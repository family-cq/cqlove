package com.cqgzs.cqlove.client;

import com.cqgzs.cqlove.PlatformMods;
import com.mojang.blaze3d.platform.IconSet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * 用 D:\cqGongZuoShi\CQ\logo.png 生成的图标覆盖游戏窗口/任务栏图标。
 *
 * <p>原版是用 {@code window.setIcon(vanillaPackResources, IconSet.RELEASE)} 读取
 * {@code icons/icon_16x16.png ... icon_256x256.png}，而 mod 资源不在 vanilla 包里，
 * 所以这里自带一个只提供这五张图的最小 {@link PackResources}，再调一次 setIcon 覆盖。</p>
 */
public final class WindowIconSetter {

    private static final String[] ICON_SIZES = {"16x16", "32x32", "48x48", "128x128", "256x256"};
    private static final String ICON_DIR = "/assets/" + PlatformMods.MODID + "/icons/";

    private WindowIconSetter() {
    }

    public static void apply() {
        Map<String, byte[]> icons = new LinkedHashMap<>();
        for (String size : ICON_SIZES) {
            String fileName = "icon_" + size + ".png";
            try (InputStream in = WindowIconSetter.class.getResourceAsStream(ICON_DIR + fileName)) {
                if (in == null) {
                    PlatformMods.LOGGER.warn("[cqlove] 缺少窗口图标资源: {}", fileName);
                    continue;
                }
                icons.put("icons/" + fileName, in.readAllBytes());
            } catch (IOException e) {
                PlatformMods.LOGGER.error("[cqlove] 读取窗口图标失败: {}", fileName, e);
            }
        }

        if (icons.isEmpty()) {
            return;
        }

        try {
            Minecraft.getInstance().getWindow().setIcon(new IconResources(icons), IconSet.RELEASE);
            PlatformMods.LOGGER.info("[cqlove] 窗口图标已替换（{} 档尺寸）", icons.size());
        } catch (IOException e) {
            PlatformMods.LOGGER.error("[cqlove] 设置窗口图标失败", e);
        }
    }

    /** 只承载 icons/*.png 的资源包实现，其余接口一律返回空。 */
    private record IconResources(Map<String, byte[]> icons) implements PackResources {

        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            byte[] data = this.icons.get(String.join("/", path));
            return data == null ? null : () -> new ByteArrayInputStream(data);
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String directory, PackResources.ResourceOutput output) {
            // 无命名空间资源，不参与资源包扫描
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return Set.of();
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> metadataSerializer) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return new PackLocationInfo("cqlove_icons", Component.literal("cqlove icons"), PackSource.BUILT_IN, Optional.empty());
        }

        @Override
        public void close() {
        }
    }
}
