# cqlove

Minecraft NeoForge 魔法模组，基于 **Minecraft 26.1.2 / NeoForge 26.1.2.126** 开发。

当前版本：**0.0.1**

## 内容概览

- **技能系**：祝福、音、诅咒、植物、亡灵、召唤、空间，共 7 系 50+ 技能
- **元素系统**：12 种元素附着（7 秒）、50+ 条双元素反应、元素护盾与 Jade tooltip 显示
- **召唤体系**：统一的召唤物登记、归属判定与 10 项属性生成（速度 / 跳跃 / 力量 / 血量 / 魔力 / 暴击 / 暴伤 / 魔暴击 / 魔暴伤 / 元素精通），原版生物同样适用
- **PVP 判定**：`/pvp on|off` 开关 + 统一伤害归属追踪
- **特效系统**：服务端只下发描述数据，客户端自建 Display 实体渲染，减少网络负载
- **提示系统**：所有反馈走屏幕中下 HUD，不占用聊天框

## 构建

需要 JDK 25。

```bash
./gradlew build
```

产物：`build/libs/cqlove-0.0.1.jar`

> Jade 是编译期可选依赖（仅用于生物 tooltip 集成），不计入产物。本地构建前把 Jade 的 26.1.x NeoForge 版本放到 `libs/jade-neoforge-26.1.12.jar`；CI 会自动下载。

## 运行

```bash
./gradlew runClient   # 客户端
./gradlew runServer   # 服务端
```

## 协议版本

客户端与服务端需版本一致（`CqloveNetwork.PROTOCOL_VERSION`），不匹配会被拒绝连接。

## 许可

All Rights Reserved.
