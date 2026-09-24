# 功能模块

KaProxy 当前提供七个可独立启用的用户功能模块。

## KaGuilds

为安装在不同子服上的 KaGuilds 提供跨服消息和在线玩家同步。

[查看 KaGuilds 模块说明](guilds.md)

## KaTpa

协调不同子服玩家之间的请求、吟唱、切服和最终传送。

[查看 KaTpa 模块说明](tpa.md)

## Back

协调 KaTpa 的 `/back` 和 `/dback` 跨服返回，管理切服与落点交付。

[查看 Back 模块说明](back.md)

## KaMenu

为 KaMenu 菜单动作提供跨服转发，使目标选择器 `{player: *}{cross}` 能触达其他子服的玩家。

[查看 KaMenu 模块说明](kamenu.md)

## KaBroadcast

为 KaBroadcast 后端转发群组公告、真正进入/离开代理网络事件和跨服死亡事件。

[查看 KaBroadcast 模块说明](broadcast.md)

## KaLogin

为 KaLogin 后端持有跨服登录会话，使玩家在一个子服登录后切换子服无需重复登录。

[查看 KaLogin 模块说明](kalogin.md)

## Lastseen

配合 KaLogin，让玩家登录后自动回到上次下线的子服与坐标。

[查看上次下线位置说明](lastseen.md)

不使用某项功能时，可以在 `config.yml` 中关闭对应模块。
