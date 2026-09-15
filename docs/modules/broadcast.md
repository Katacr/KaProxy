# KaBroadcast 群组公共事件

KaProxy 的 `broadcast` 模块为 KaBroadcast 后端提供群组公告、统一加入/退出消息和跨服死亡消息。

## 职责边界

- KaProxy 只负责判断真实代理网络进出、校验协议和转发结构化内容。
- KaProxy 不保存加入、退出、公告或死亡消息模板。
- 每个子服的 KaBroadcast 独立配置最终显示文本，因此可以按子服语言和风格定制。
- 所有参与群组公共消息的后端都应安装并启用 KaBroadcast。

## 配置

```yaml
modules:
  broadcast:
    enabled: true
    announcements-enabled: true
    connection-messages-enabled: true
    death-messages-enabled: true
    servers:
      - all
```

`servers` 为 `all` 或 `*` 时转发到所有后端；配置服务器列表时只发送到列出的后端。

## 加入、退出与切服

KaProxy 保存在线玩家当前所在后端：

1. 玩家首次连接到任意后端时，发送一次 `player_join`。
2. 玩家在后端之间切换时，向目标后端发送 `switch_request` 获取切服渲染结果，再转发 `player_switch`；不发送加入或退出事件。
3. 玩家真正断开代理时，发送一次 `player_quit`。

切服判定使用代理平台事件（Velocity `ServerPostConnectEvent.getPreviousServer()`），并由后端 `modules.connection.switch-message-enabled` 决定是否显示。

KaBroadcast 后端负责屏蔽 Bukkit 原生加入和退出消息，并使用本地模板显示代理事件。

## 定向公告

后端可用 `broadcast/publish_targeted` 请求把公告只发给某个玩家。KaProxy 按名称解析目标玩家，仅向该玩家所在后端发送 `broadcast/announcement_targeted`，由该后端渲染并使用 Bukkit API 投递给目标玩家。

## 死亡消息

KaBroadcast 后端读取 Bukkit 最终死亡文本，屏蔽本服原生死亡消息后发送 `death`。KaProxy 添加来源后端并转发为 `player_death`，各后端再使用自己的死亡模板显示。

所有公共事件都带有 UUID `eventId`，后端和代理会进行短期去重，避免同一事件重复显示。

## 协议动作

后端发送：

- `broadcast/publish`：公告，负载为 `eventId`、发送者和消息。
- `broadcast/publish_targeted`：定向公告，负载为 `eventId`、目标玩家名称、发送者和消息。
- `broadcast/death`：死亡事件，负载为 `eventId`、玩家 UUID、玩家名称、纯文本回退值和组件 JSON。
- `broadcast/switch_publish`：目标后端渲染完成的切服结果。

代理转发：

- `broadcast/announcement`
- `broadcast/announcement_targeted`：定向公告，只发往目标玩家所在后端。
- `broadcast/player_join`
- `broadcast/player_switch`
- `broadcast/player_quit`
- `broadcast/player_death`：携带纯文本回退值和组件 JSON；旧版后端缺少组件 JSON 时代理会生成纯文本组件。
- `broadcast/switch_request`：向切服目标后端索取切服渲染结果。
