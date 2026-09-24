# KaProxy

KaProxy 是 Ka 系列插件共用的跨服事务代理，单个 JAR 同时支持 Velocity 3.4 和 BungeeCord 1.21。当前内置 KaGuilds 消息模块、KaTpa 事务模块和 KaBroadcast 群组公共事件模块，后续插件可继续使用版本化的 `kaproxy:main` 协议接入。

## 用户文档

- [中文文档](docs/README.md)
- [English Documentation](docs-en/README.md)

## 模块

- `guilds`：转发 `kaguilds:chat` 消息，并向各子服发送代理网络的在线玩家列表。
- `tpa`：管理跨服请求 UUID、同意、拒绝、撤销、超时、源服吟唱、玩家切服、目标服到达凭证与完成确认。
- `broadcast`：判断玩家是否真正进入或离开代理网络，转发公告、统一加入/退出事件和跨服死亡事件；消息模板由各后端 KaBroadcast 独立配置。
- `kalogin`：持有 KaLogin 跨服登录会话，让玩家在一个子服登录后切换子服无需重复登录。
- `lastseen`：配合 KaLogin 记录上次下线位置，玩家登录后自动回到上次所在的子服与坐标。
- `core`：维护玩家 UUID、名称和当前子服，提供版本校验、定向后端消息及管理命令。

## 安装

1. 将 `KaProxy-1.0.0.jar` 放入 Velocity 或 BungeeCord 的 `plugins` 文件夹。
2. 首次启动生成 `plugins/kaproxy/config.yml` 和 `plugins/kaproxy/lang/`。
3. KaGuilds 子服使用 `proxy: true`，所有子服连接相同 MySQL 数据库。
4. KaTpa 子服设置 `proxy.enabled: true`，并建议将 `storage.type` 设为 `mysql`，让设置和名单跨服共享。

Velocity 数据目录名称由插件 ID 决定，通常为 `plugins/kaproxy`；BungeeCord 通常为 `plugins/KaProxy`。

## 配置

```yaml
core:
  language: zh_CN
  log-unknown-modules: false

# 跨服位置记录数据库（须与各后端 KaLogin 指向同一库）
database:
  host: "localhost"
  port: 3306
  database: "kalogin"
  username: "root"
  password: ""
  params: "?useSSL=false&serverTimezone=UTC"

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

  broadcast:
    enabled: true
    announcements-enabled: true
    connection-messages-enabled: true
    death-messages-enabled: true
    servers:
      - all

  kalogin:
    enabled: true
    bind-ip: true
    debug: false

  lastseen:
    enabled: true
    connect-directly: true
    default-server: "lobby"
    blacklist: "pve,pvp"
    debug: false
```

`follow-target-server` 启用后，目标玩家在吟唱期间切换子服时，旅行者会前往其最新子服。关闭后目标换服会中断事务。

所有日志和命令文本位于 `lang/zh_CN.yml`、`lang/en_US.yml`。修改 `core.language` 后执行 `/kaproxy reload` 即可切换。

## 命令

- `/kaproxy status`：查看 Guilds、Tpa、Back、Kamenu、Broadcast 模块状态和代理在线人数。
- `/kaproxy reload`：重载配置和语言，需要 `kaproxy.admin`。

## 构建

```bash
cd /home/plugins/KaProxy
bash ./gradlew clean build
```

成品位于 `build/libs/KaProxy-1.0.0.jar`。插件不打包 Velocity、BungeeCord 或数据库依赖。
