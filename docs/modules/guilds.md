# KaGuilds 跨服兼容

KaProxy 可以代替旧版 KaGuilds 代理插件，为各子服上的 KaGuilds 转发公会消息并同步代理在线玩家。

## 使用条件

* KaProxy 已安装在 Velocity 或 BungeeCord 上。
* 每个参与公会网络的子服都安装 KaGuilds。
* KaGuilds 的代理功能已启用。
* 所有 KaGuilds 子服连接同一个 MySQL 数据库。

## 推荐配置

KaProxy：

```yaml
modules:
  guilds:
    enabled: true
    legacy-channel-enabled: true
    sync-player-list: true
```

KaGuilds 子服：

```yaml
proxy: true
```

具体字段位置以当前 KaGuilds 版本的配置文件为准。

## 从旧代理插件迁移

如果代理端正在使用 `KaGuildsProxy.jar`：

1. 关闭代理。
2. 将旧 JAR 移出代理的 `plugins` 文件夹。
3. 放入 KaProxy JAR。
4. 保持各子服 KaGuilds 的代理配置不变。
5. 启动代理并测试跨服公会消息。

不要让旧代理插件与 KaProxy 同时运行，否则同一条消息可能被转发多次。

## 验证方法

1. 使用两个测试玩家分别进入不同子服。
2. 确认两个玩家属于同一公会或满足当前聊天测试条件。
3. 从其中一个子服发送公会消息。
4. 确认另一子服能够正常收到，且消息只出现一次。

## 何时关闭玩家列表同步

仅在以下情况考虑将 `sync-player-list` 设置为 `false`：

* 当前 KaGuilds 版本不需要代理在线列表。
* 网络中已有其他明确兼容的在线列表来源。
* 正在排查重复在线列表问题。

一般情况下建议保持默认值 `true`。
