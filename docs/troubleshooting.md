# 常见问题

## 快速检查清单

遇到跨服功能异常时，请依次确认：

1. KaProxy 是否安装在代理端，而不是子服。
2. 代理是否使用 Java 21。
3. 启动日志是否显示 KaProxy 已成功加载。
4. `/kaproxy status` 是否显示对应模块为启用。
5. 每个相关子服是否安装并启用了 KaGuilds 或 KaTpa 的代理功能。
6. 测试玩家是否确实通过同一个代理在线。
7. 修改配置后是否执行了 `/kaproxy reload`。

## 常见现象

### KaProxy 没有生成配置目录

* 确认 JAR 位于代理的 `plugins` 文件夹。
* 确认使用 Java 21。
* 查看代理启动日志中的插件加载错误。
* Velocity 通常生成 `plugins/kaproxy/`，BungeeCord 通常生成 `plugins/KaProxy/`。

### 公会消息出现两次

最常见原因是 KaProxy 与旧版 `KaGuildsProxy.jar` 同时运行。

关闭代理，将旧代理 JAR 移出 `plugins` 文件夹，然后重新启动。

### 公会消息不能跨服

* 检查 `modules.guilds.enabled` 和 `legacy-channel-enabled` 是否为 `true`。
* 检查各子服 KaGuilds 的代理选项是否启用。
* 确认所有 KaGuilds 子服连接相同数据库。
* 确认发送和接收玩家位于同一代理网络。

### TPA 只能在同一个子服使用

* 检查 `modules.tpa.enabled` 是否为 `true`。
* 检查每个子服 KaTpa 的 `proxy.enabled` 是否为 `true`。
* 确认两名玩家通过同一个代理在线。
* 确认各子服使用兼容的 KaTpa 版本。

### 接受请求后没有切服

* 确认目标子服在代理配置中存在且在线。
* 检查玩家是否完成了子服配置的吟唱。
* 查看子服是否因为移动、受伤或其他规则取消吟唱。
* 适当提高 `transaction-timeout-seconds` 后重新测试。

### 提示目标移动或请求失效

如果目标玩家在传送期间切服：

* 希望继续跟随目标时，将 `follow-target-server` 设置为 `true`。
* 希望固定原目标服时保持 `false`，玩家需要重新发送请求。

请求超时、玩家离线或重复操作也可能使旧请求失效。

### 修改语言后没有生效

* 确认 `core.language` 与 `lang` 文件名完全一致，不包含 `.yml`。
* 确认语言文件使用 UTF-8 保存。
* 执行 `/kaproxy reload`。

### 重载失败

* 检查 `config.yml` 的缩进是否使用空格。
* 检查布尔值是否为 `true` 或 `false`。
* 检查时间配置是否为纯数字秒数。
* 恢复最近一次可用备份，再执行重载。

## 收集排障信息

反馈问题时建议提供：

* 代理类型和版本
* Java 版本
* KaProxy、KaGuilds、KaTpa 版本
* `/kaproxy status` 输出
* 已隐藏 IP、密码和数据库信息的相关配置
* 问题发生前后的代理与子服日志

不要公开数据库密码、代理转发密钥或玩家隐私信息。
