---
description: KaProxy - Ka 系列插件的 Velocity 与 BungeeCord 跨服代理
---

# 首页

> 使用一个代理插件，为 KaGuilds 和 KaTpa 提供统一、稳定的跨服体验。

**KaProxy** 安装在 Velocity 或 BungeeCord 代理端，不需要安装到每个 Minecraft 子服。它让 Ka 系列插件能够识别玩家当前所在子服，并协调跨服公会消息与跨服传送请求。

> **运行要求**
>
> - Java 21
> - Velocity 3.4 或 BungeeCord 1.21
> - 每个参与跨服功能的子服安装对应的 Ka 系列插件
> - 所有子服必须连接到同一个代理网络

[English documentation](../docs-en/README.md)

***

## 主要功能

### KaGuilds 跨服兼容

* 在不同子服之间转发 KaGuilds 公会消息
* 同步代理网络内的在线玩家与所在子服
* 兼容现有 KaGuilds 跨服配置，无需重新设计公会系统

### KaTpa 跨服传送

* 支持玩家向其他子服的玩家发送 TPA 和 TPAHERE 请求
* 统一处理接受、拒绝、撤销、冷却和超时
* 在传送前保留原子服的吟唱体验
* 自动将玩家切换到目标所在子服，再由目标服完成最终传送
* 可选择在目标玩家切服后继续跟随其最新子服

### 双平台代理

同一个 `KaProxy-1.0.0.jar` 可用于：

* Velocity 3.4
* BungeeCord 1.21

***

## 快速导航

* [快速开始](getting-started.md)
* [配置说明](configuration.md)
* [KaGuilds 模块](modules/guilds.md)
* [KaTpa 模块](modules/tpa.md)
* [命令与权限](commands.md)
* [升级与迁移](migration.md)
* [常见问题](troubleshooting.md)

## 重要说明

* KaProxy 只安装在代理端，不要放入 Paper、Folia 或 Spigot 子服的 `plugins` 文件夹。
* 同一代理上不要同时运行 KaProxy 和旧版 `KaGuildsProxy.jar`，否则公会消息可能被重复转发。
* KaProxy 不代替 KaGuilds 或 KaTpa；子服仍需安装并配置相应插件。
* 配置或语言文件修改后，可使用 `/kaproxy reload` 重载。
