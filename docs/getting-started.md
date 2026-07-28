# 快速开始

本页介绍如何在现有 Velocity 或 BungeeCord 网络中安装 KaProxy。

## 安装前准备

请先确认：

* 代理使用 Java 21 运行。
* 代理为 Velocity 3.4 或 BungeeCord 1.21。
* 后端子服已经可以通过代理正常进入。
* 需要跨服公会功能时，各子服已安装 KaGuilds。
* 需要跨服传送功能时，各子服已安装兼容的 KaTpa。

## 安装 KaProxy

1. 关闭代理服务器。
2. 将 `KaProxy-1.0.0.jar` 放入代理的 `plugins` 文件夹。
3. 启动代理。
4. 等待 KaProxy 生成默认配置和语言文件。

默认数据目录通常为：

| 平台 | 数据目录 |
|------|----------|
| Velocity | `plugins/kaproxy/` |
| BungeeCord | `plugins/KaProxy/` |

不同代理实现可能调整插件目录名称，请以启动后实际生成的目录为准。

## 配置后端插件

### 使用 KaGuilds

在所有需要参与跨服公会功能的子服中：

1. 安装 KaGuilds。
2. 将 KaGuilds 的代理功能设置为启用，例如 `proxy: true`。
3. 让所有子服连接同一个 MySQL 数据库。
4. 重启或重载对应的子服插件。

### 使用 KaTpa

在所有需要参与跨服传送的子服中：

1. 安装 KaTpa。
2. 将 KaTpa 的代理功能设置为启用，例如 `proxy.enabled: true`。
3. 建议所有子服使用同一个 MySQL 数据库，使玩家设置和名单在各服保持一致。
4. 重启或重载对应的子服插件。

## 验证安装

在代理控制台执行：

```text
kaproxy status
```

正常情况下会显示 Guilds、Tpa 模块状态和代理在线人数。

随后可以使用两个位于不同子服的测试账号进行验证：

* 发送一条 KaGuilds 公会消息，确认另一子服可以收到。
* 发送一次 KaTpa 请求，确认接受后能够切换子服并到达目标玩家。

如果功能没有生效，请查看[常见问题](troubleshooting.md)。
