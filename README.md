# KaProxy

KaProxy 是 Ka 系列插件共用的跨服事务代理，单个 JAR 同时支持 Velocity 3.4 和 BungeeCord 1.21。当前内置 KaGuilds 兼容模块与 KaTpa 事务模块，后续插件可继续使用版本化的 `kaproxy:main` 协议接入。

## 用户文档

- [中文文档](docs/README.md)
- [English Documentation](docs-en/README.md)

## 模块

- `guilds`：兼容转发原有 `kaguilds:chat`，并继续向各子服发送旧格式全服在线玩家列表。现有 KaGuilds 不需要修改通讯代码。
- `tpa`：管理跨服请求 UUID、同意、拒绝、撤销、超时、源服吟唱、玩家切服、目标服到达凭证与完成确认。
- `core`：维护玩家 UUID、名称和当前子服，提供版本校验、定向后端消息及管理命令。

## 安装

1. 将 `KaProxy-1.0.0.jar` 放入 Velocity 或 BungeeCord 的 `plugins` 文件夹。
2. 删除代理端正在运行的 `KaGuildsProxy.jar`，避免旧公会消息被重复转发；原项目源码可以保留。
3. 首次启动生成 `plugins/kaproxy/config.yml` 和 `plugins/kaproxy/lang/`。
4. KaGuilds 子服继续使用 `proxy: true`，所有子服连接相同 MySQL 数据库。
5. KaTpa 子服设置 `proxy.enabled: true`，并建议将 `storage.type` 设为 `mysql`，让设置和名单跨服共享。

Velocity 数据目录名称由插件 ID 决定，通常为 `plugins/kaproxy`；BungeeCord 通常为 `plugins/KaProxy`。

## 配置

```yaml
core:
  language: zh_CN
  log-unknown-modules: false

modules:
  guilds:
    enabled: true
    legacy-channel-enabled: true
    sync-player-list: true

  tpa:
    enabled: true
    request-timeout-min-seconds: 5
    request-timeout-max-seconds: 120
    transaction-timeout-seconds: 60
    cooldown-max-seconds: 3600
    follow-target-server: true
```

`follow-target-server` 启用后，目标玩家在吟唱期间切换子服时，旅行者会前往其最新子服。关闭后目标换服会中断事务。

所有日志和命令文本位于 `lang/zh_CN.yml`、`lang/en_US.yml`。修改 `core.language` 后执行 `/kaproxy reload` 即可切换。

## 命令

- `/kaproxy status`：查看 Guilds、Tpa 模块状态和代理在线人数。
- `/kaproxy reload`：重载配置和语言，需要 `kaproxy.admin`。

## 构建

```bash
cd /home/plugins/KaProxy
bash ./gradlew clean build
```

成品位于 `build/libs/KaProxy-1.0.0.jar`。插件不打包 Velocity、BungeeCord 或数据库依赖。
