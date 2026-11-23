package com.cqgzs.cqlove.lan;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

/**
 * 自定义“对局域网开放”界面（需求文档 二.2）。
 *
 * <p>替换原版 {@code ShareToLanScreen}（由 {@code LanScreenHook} 在
 * {@code ScreenEvent.Opening} 中完成替换），布局：</p>
 * <pre>
 *                 局域网世界            (固定标题, c)
 *              对其他玩家的设置          (固定副标题, d)
 *   [游戏模式]            [允许命令]      (e/f)
 *   [联机模式]            [允许PVP]       (g/h)
 *   [监听端口]            [玩家最大数]    (i/j)
 *   [保存配置]            [自动读取]      (a/b)
 *   [创建局域网]          [取消]          (k/l)
 * </pre>
 */
public class LanShareScreen extends Screen {

    // ---- 固定文案（需求文档 c/d 项：固定标题、固定副标题） ----
    private static final Component TITLE_TEXT = Component.literal("局域网世界");
    private static final Component SUBTITLE_TEXT = Component.literal("对其他玩家的设置");

    // ---- 各控件标签 ----
    private static final Component GAME_MODE_LABEL = Component.literal("游戏模式");
    private static final Component ALLOW_COMMANDS_LABEL = Component.literal("允许命令");
    private static final Component ONLINE_MODE_LABEL = Component.literal("联机模式");
    private static final Component PVP_LABEL = Component.literal("允许PVP");
    private static final Component PORT_LABEL = Component.literal("监听端口");
    private static final Component MAX_PLAYERS_LABEL = Component.literal("玩家最大数");
    private static final Component SAVE_LABEL = Component.literal("保存配置");
    private static final Component AUTO_LOAD_LABEL = Component.literal("自动读取已储存配置");
    private static final Component CREATE_LABEL = Component.literal("创建局域网");

    // ---- 端口/人数校验提示 ----
    private static final Component INVALID_PORT =
            Component.literal("无效端口, 必须在 1024-65535 之间").withStyle(ChatFormatting.RED);
    private static final Component PORT_UNAVAILABLE =
            Component.literal("端口被占用").withStyle(ChatFormatting.RED);
    private static final Component INVALID_MAX_PLAYERS =
            Component.literal("人数必须为 1-" + LanSettings.MAX_PLAYERS_LIMIT).withStyle(ChatFormatting.RED);

    /** 返回时要回到的界面（通常是暂停菜单） */
    private final Screen lastScreen;

    // ---- 控件当前值（与 LanSettings 一一对应） ----
    private GameType gameMode = GameType.SURVIVAL;      // e 默认生存模式
    private boolean allowCommands = false;              // f 默认关闭
    private OnlineModeSetting onlineMode = OnlineModeSetting.ONLINE; // g 默认在线模式
    private boolean pvpAllowed = false;                 // h 默认关闭
    private int port = LanSettings.DEFAULT_PORT;        // i 默认 25565
    private int maxPlayers = LanSettings.DEFAULT_MAX_PLAYERS; // j 默认 8
    private boolean autoLoad = true;                    // b 默认开

    private @Nullable EditBox portEdit;
    private @Nullable EditBox maxPlayersEdit;
    private @Nullable Button createButton;

    public LanShareScreen(Screen lastScreen) {
        super(TITLE_TEXT);
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        // 打开界面时：配置文件存在且“自动读取已储存配置”为开 -> 用储存的值初始化控件（需求文档 b 项）
        if (LanSettings.configFileExists()) {
            LanSettings stored = LanSettings.load();
            if (stored.autoLoad) {
                applySettings(stored);
            }
        }

        int left = this.width / 2 - 155;
        int right = this.width / 2 + 5;
        int columnWidth = 150;
        int rowHeight = 20;

        // 第一行：游戏模式(e) / 允许命令(f)
        this.addRenderableWidget(
                CycleButton.builder(GameType::getShortDisplayName, this.gameMode)
                        .withValues(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR)
                        .create(left, 74, columnWidth, rowHeight, GAME_MODE_LABEL, (button, value) -> this.gameMode = value));
        this.addRenderableWidget(
                CycleButton.onOffBuilder(this.allowCommands)
                        .create(right, 74, columnWidth, rowHeight, ALLOW_COMMANDS_LABEL, (button, value) -> this.allowCommands = value));

        // 第二行：联机模式(g) / 允许PVP(h)
        this.addRenderableWidget(
                CycleButton.builder(OnlineModeSetting::getShortName, this.onlineMode)
                        .withValues(OnlineModeSetting.values())
                        .create(left, 98, columnWidth, rowHeight, ONLINE_MODE_LABEL, (button, value) -> this.onlineMode = value));
        this.addRenderableWidget(
                CycleButton.onOffBuilder(this.pvpAllowed)
                        .create(right, 98, columnWidth, rowHeight, PVP_LABEL, (button, value) -> this.pvpAllowed = value));

        // 第三行：监听端口(i) / 玩家最大数(j)，输入框上方绘制小标签（见 extractRenderState）
        this.portEdit = new EditBox(this.font, left, 122, columnWidth, rowHeight, PORT_LABEL);
        this.portEdit.setValue(String.valueOf(this.port));
        this.portEdit.setResponder(value -> validateInputs());
        this.addRenderableWidget(this.portEdit);

        this.maxPlayersEdit = new EditBox(this.font, right, 122, columnWidth, rowHeight, MAX_PLAYERS_LABEL);
        this.maxPlayersEdit.setValue(String.valueOf(this.maxPlayers));
        this.maxPlayersEdit.setResponder(value -> validateInputs());
        this.addRenderableWidget(this.maxPlayersEdit);

        // 第四行：保存配置(a) / 自动读取已储存配置(b)
        this.addRenderableWidget(
                Button.builder(SAVE_LABEL, button -> saveCurrentSettings())
                        .bounds(left, 146, columnWidth, rowHeight).build());
        this.addRenderableWidget(
                CycleButton.onOffBuilder(this.autoLoad)
                        .create(right, 146, columnWidth, rowHeight, AUTO_LOAD_LABEL, (button, value) -> this.autoLoad = value));

        // 底部：创建局域网(k) / 取消(l)
        this.createButton = Button.builder(CREATE_LABEL, button -> createLanServer())
                .bounds(left, this.height - 28, columnWidth, rowHeight).build();
        this.addRenderableWidget(this.createButton);
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                        .bounds(right, this.height - 28, columnWidth, rowHeight).build());

        // 初始化时先校验一次，保证非法初始值（理论上不会）禁用创建按钮
        validateInputs();
    }

    /** 把一份配置写入成员变量（初始化控件前值） */
    private void applySettings(LanSettings settings) {
        this.gameMode = settings.gameType();
        this.allowCommands = settings.allowCommands;
        this.onlineMode = settings.onlineModeSetting();
        this.pvpAllowed = settings.pvpAllowed;
        this.port = settings.port;
        this.maxPlayers = settings.maxPlayers;
        this.autoLoad = settings.autoLoad;
    }

    /** 读取两个输入框并校验；非法时禁用“创建局域网”按钮并给出红色提示 */
    private void validateInputs() {
        boolean portOk = parsePort();
        boolean maxPlayersOk = parseMaxPlayers();
        if (this.portEdit != null) {
            this.portEdit.setTextColor(portOk ? 0xFFE0E0E0 : 0xFFFF5555);
            this.portEdit.setTooltip(portOk ? null : Tooltip.create(INVALID_PORT));
        }
        if (this.maxPlayersEdit != null) {
            this.maxPlayersEdit.setTextColor(maxPlayersOk ? 0xFFE0E0E0 : 0xFFFF5555);
            this.maxPlayersEdit.setTooltip(maxPlayersOk ? null : Tooltip.create(INVALID_MAX_PLAYERS));
        }
        if (this.createButton != null) {
            this.createButton.active = portOk && maxPlayersOk;
        }
    }

    /** 解析端口输入：合法范围 1024-65535（与原版一致） */
    private boolean parsePort() {
        try {
            int parsed = Integer.parseInt(this.portEdit.getValue().trim());
            if (parsed < 1024 || parsed > 65535) {
                return false;
            }
            this.port = parsed;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 解析玩家最大数输入：1-1024 */
    private boolean parseMaxPlayers() {
        try {
            int parsed = Integer.parseInt(this.maxPlayersEdit.getValue().trim());
            if (parsed < 1 || parsed > LanSettings.MAX_PLAYERS_LIMIT) {
                return false;
            }
            this.maxPlayers = parsed;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** “保存配置”按钮(a)：把当前页面配置元素储存到 JSON 文件 */
    private void saveCurrentSettings() {
        if (!validateBeforeUse()) {
            return; // 输入非法时不保存，避免写坏配置
        }
        LanSettings settings = new LanSettings();
        settings.gameMode = this.gameMode.getName();
        settings.allowCommands = this.allowCommands;
        settings.onlineMode = this.onlineMode.getId();
        settings.pvpAllowed = this.pvpAllowed;
        settings.port = this.port;
        settings.maxPlayers = this.maxPlayers;
        settings.autoLoad = this.autoLoad;
        settings.save();
        com.cqgzs.cqlove.client.ClientChat.message(Component.literal("局域网配置已保存"));
    }

    /** 使用前的最终校验；失败时聊天栏提示 */
    private boolean validateBeforeUse() {
        validateInputs();
        if (this.createButton != null && !this.createButton.active) {
            com.cqgzs.cqlove.client.ClientChat.message(
                    Component.literal("端口或人数输入不合法, 无法继续").withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    /**
     * “创建局域网”按钮(k)：按页面配置修改服务器设置并开放局域网，
     * 然后回到游戏主界面，并发送红色端口号的消息。
     */
    private void createLanServer() {
        if (!validateBeforeUse()) {
            return;
        }
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        // 1) 联机模式(g)：在线模式=正版验证开；UUID修复+离线模式=正版验证关+UUID修复开
        server.setUsesAuthentication(this.onlineMode.isOnline());
        LanRuntime.setUuidFixEnabled(this.onlineMode == OnlineModeSetting.OFFLINE_UUID_FIX);

        // 2) 玩家最大数(j)：由 IntegratedServerMixin#getMaxPlayers 读取
        LanRuntime.setLanMaxPlayers(this.maxPlayers);

        // 3) 允许PVP(h)：写入 PVP API，供伤害判定统一调用
        PvpApi.setPvpAllowed(this.pvpAllowed);

        // 4) 检查端口可用性，不可用则提示并不执行开放
        if (!HttpUtil.isPortAvailable(this.port)) {
            com.cqgzs.cqlove.client.ClientChat.message(PORT_UNAVAILABLE);
            return;
        }

        // 5) 回到游戏主界面并开放局域网（与原版流程一致）
        this.minecraft.setScreen(null);
        Component message;
        if (server.publishServer(this.gameMode, this.allowCommands, this.port)) {
            // 需求文档 k 项：消息“以开放局域网世界,端口xxx”，端口红色
            message = Component.literal("以开放局域网世界,端口")
                    .append(Component.literal(String.valueOf(this.port)).withStyle(ChatFormatting.RED));
        } else {
            message = Component.translatable("commands.publish.failed");
        }
        this.minecraft.gui.getChat().addClientSystemMessage(message);
        this.minecraft.getNarrator().saySystemQueued(message);
        this.minecraft.updateTitle();
    }

    /** “取消”按钮(l)：关闭当前页面，不保存，不进行任何操作 */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.lastScreen);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        // 固定标题(c) / 固定副标题(d)
        graphics.centeredText(this.font, TITLE_TEXT, this.width / 2, 20, -1);
        graphics.centeredText(this.font, SUBTITLE_TEXT, this.width / 2, 50, -1);
        // 两个输入框的小标签
        graphics.centeredText(this.font, PORT_LABEL, this.width / 2 - 80, 110, -1);
        graphics.centeredText(this.font, MAX_PLAYERS_LABEL, this.width / 2 + 80, 110, -1);
    }
}
