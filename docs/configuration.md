# 配置说明

KaProxy 的主配置文件位于插件数据目录下的 `config.yml`。

修改配置后执行：

```text
kaproxy reload
```

## 默认配置

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

  back:
    enabled: true
    transaction-timeout-seconds: 30

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

## 基础设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `core.language` | `zh_CN` | 使用 `lang` 文件夹中的语言文件名，不包含 `.yml`。 |
| `core.log-unknown-modules` | `false` | 是否记录无法识别的扩展消息。通常保持关闭，排障时再开启。 |

## 数据库设置

`lastseen` 模块使用共享 MySQL 存放上次位置，须与各后端 KaLogin 指向同一库（复用 `kalogin`）。仅支持 MySQL；未配置时该模块直连不可用，回退默认服。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `database.host` | `localhost` | MySQL 主机。 |
| `database.port` | `3306` | MySQL 端口。 |
| `database.database` | `kalogin` | 库名（与后端 KaLogin 相同）。 |
| `database.username` | `root` | 用户名。 |
| `database.password` | 空 | 密码。 |
| `database.params` | `?useSSL=false&serverTimezone=UTC` | JDBC 附加参数。 |

## Guilds 设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `modules.guilds.enabled` | `true` | 是否启用 KaGuilds 跨服兼容。 |
| `modules.guilds.legacy-channel-enabled` | `true` | 是否兼容现有 KaGuilds 的跨服通讯方式。已有网络建议保持开启。 |
| `modules.guilds.sync-player-list` | `true` | 是否向各子服同步代理在线玩家及所在子服。 |

## Tpa 设置

| 配置项 | 默认值 | 可用范围 | 说明 |
|--------|--------|----------|------|
| `modules.tpa.enabled` | `true` | `true` / `false` | 是否启用跨服传送。关闭时，进行中的跨服请求会被取消。 |
| `request-timeout-min-seconds` | `5` | 1–3600 秒 | 子服允许提交的最短请求有效期。 |
| `request-timeout-max-seconds` | `120` | 最短值–86400 秒 | 子服允许提交的最长请求有效期。 |
| `transaction-timeout-seconds` | `60` | 10–600 秒 | 请求接受后，吟唱、切服和最终到达必须完成的总时间。 |
| `cooldown-max-seconds` | `3600` | 0–86400 秒 | 代理允许保存的最大跨服请求冷却时间。`0` 表示不允许额外冷却。 |
| `follow-target-server` | `true` | `true` / `false` | 目标玩家切服后，是否继续前往其最新子服。 |

数值超出允许范围时会被限制到有效范围；无法识别的数值会使用默认值。

## Back 设置

| 配置项 | 默认值 | 可用范围 | 说明 |
|--------|--------|----------|------|
| `modules.back.enabled` | `true` | `true` / `false` | 是否启用跨服返回（/back 和 /dback）。关闭时，进行中的跨服返回会被取消。 |
| `transaction-timeout-seconds` | `30` | 5–600 秒 | 切服和落点传送必须完成的总时间。 |

## Kamenu 设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `modules.kamenu.enabled` | `false` | 是否启用 KaMenu 跨服动作转发。 |
| `modules.kamenu.servers` | — | 允许接收跨服动作的后端列表。设为 `all` 或 `*` 表示转发到所有后端（排除来源服）。 |

## Broadcast 设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `modules.broadcast.enabled` | `true` | 是否启用 KaBroadcast 群组公共事件转发。 |
| `modules.broadcast.announcements-enabled` | `true` | 是否允许后端发送和接收群组公告。 |
| `modules.broadcast.connection-messages-enabled` | `true` | 是否转发真实进入和离开代理网络的事件。子服切换不会产生事件。 |
| `modules.broadcast.death-messages-enabled` | `true` | 是否转发跨服死亡事件。 |
| `modules.broadcast.servers` | `all` | 接收事件的后端列表。`all` 或 `*` 表示全部后端。 |

KaProxy 只转发结构化内容，不保存消息模板。加入、退出、公告和死亡文本由各子服 KaBroadcast 配置。

## Kalogin 设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `modules.kalogin.enabled` | `true` | 是否启用 KaLogin 跨服登录会话。 |
| `modules.kalogin.bind-ip` | `true` | 会话是否绑定登录时的 IP；来源 IP 变化时拒绝恢复登录态。 |
| `modules.kalogin.debug` | `false` | 是否记录会话建立、查询与失效的决策日志。 |

详见 [KaLogin 跨服登录会话](modules/kalogin.md)。

## Lastseen 设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `modules.lastseen.enabled` | `true` | 是否启用上次下线位置自动前往。 |
| `modules.lastseen.connect-directly` | `true` | 初次连接时是否直接把玩家连到上次所在子服（消除双重进服）。 |
| `modules.lastseen.default-server` | `lobby` | 目标世界无效时回退的默认子服；留空表示停留在当前子服。 |
| `modules.lastseen.blacklist` | 空 | 不记录位置的子服列表（逗号分隔，大小写不敏感）。 |
| `modules.lastseen.debug` | `false` | 是否记录位置更新、前往与回退的决策日志。 |

详见 [上次下线位置](modules/lastseen.md)。

## 语言文件

语言文件位于：

```text
lang/zh_CN.yml
lang/en_US.yml
```

切换英文：

```yaml
core:
  language: en_US
```

可以复制任一语言文件创建自定义语言，例如 `lang/my_lang.yml`，然后将 `core.language` 设置为 `my_lang`。修改完成后执行 `/kaproxy reload`。
