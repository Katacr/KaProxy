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

## 基础设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `core.language` | `zh_CN` | 使用 `lang` 文件夹中的语言文件名，不包含 `.yml`。 |
| `core.log-unknown-modules` | `false` | 是否记录无法识别的扩展消息。通常保持关闭，排障时再开启。 |

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
