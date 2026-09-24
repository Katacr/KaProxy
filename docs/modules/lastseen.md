# 上次下线位置

KaProxy 的 `lastseen` 模块与 KaLogin 后端配合，让玩家登录后自动回到上次下线的子服与坐标，替代每次都从默认服（如 Lobby）开始。

位置记录存放在共享 MySQL 的 `kalogin_lastseen` 表（由后端 KaLogin 建表），因此代理重启、跨服并发都不丢数据。

## 职责边界

- **坐标**由后端 KaLogin 在玩家退服（含切服）时直接写入共享库，不依赖插件消息载体，因此单人子服退服也能可靠记录。
- **`server` 列**由代理在玩家**真正离开代理**时写入（切服不写），只有 server 非空的记录才是代理确认的离开位置。
- 代理在玩家初次连接时异步读库决定初始子服；认证完成（后端发送 `ready`）后投递坐标传送。
- `/logout`、注销会删除记录。

## 配置

```yaml
database:
  host: "localhost"
  port: 3306
  database: "kalogin"
  username: "root"
  password: ""
  params: "?useSSL=false&serverTimezone=UTC"

modules:
  lastseen:
    enabled: true
    connect-directly: true
    default-server: "lobby"
    blacklist: "pve,pvp"
    debug: false
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `database.*` | - | 跨服位置数据库，须与各后端 KaLogin 指向同一库（复用 `kalogin`）。 |
| `modules.lastseen.enabled` | `true` | 是否启用上次下线位置自动前往。 |
| `modules.lastseen.connect-directly` | `true` | 初次连接时直接把玩家连到上次所在子服（见下节）。 |
| `modules.lastseen.default-server` | `lobby` | 目标世界无效时回退的默认子服；留空表示停留在当前子服。 |
| `modules.lastseen.blacklist` | 空 | 不记录位置的子服列表（逗号分隔，大小写不敏感）。 |
| `modules.lastseen.debug` | `false` | 是否记录位置更新、前往与回退的决策日志。 |

## 位置黑名单

副本（PVE）、竞技场（PVP）等子服可能只在活动期间才有意义，其余时间地图处于"死"状态但服务器仍在运行。若把玩家在这些服下线的位置记录下来，下次登录会被送回一个无意义的地图。

`blacklist` 用于避免这种情况：

- 玩家从黑名单子服**真正离开代理**时，代理删除其记录。
- 于是玩家下次登录时没有记录，走 `default-server`（如 Lobby）。
- 匹配大小写不敏感；`blacklist: "pve,pvp"` 或每项单独一行均可。

> 仅影响"从该服下线后回哪里"。玩家在活动期间主动切到 PVE/PVP 不受影响；该服内正常游玩、切服逻辑照旧。

## 初始直连（消除双重进服）

开启 `connect-directly` 后，代理在玩家**初次连接**时异步读库，并把初始服务器设为上次所在子服（Velocity `PlayerChooseInitialServerEvent` + `EventTask.resumeWhenComplete`、Bungee `PostLoginEvent.registerIntent`/`completeIntent`），玩家直接在该子服完成登录并触发登录逻辑。

这样可以避免"先落默认服（Lobby）再切换到目标服"造成的双重进服：两套 `PlayerJoinEvent`、两次区块下发与各插件监听/数据库访问。

- 读库在登录阶段异步完成，代理会等待结果后再进入子服；超时/失败回退默认服。
- 目标服必须已注册且 ping 探活为在线，否则回退默认服（代理刚启动、尚未完成首次探活时同样回退）。
- 无位置记录（首次进入、记录被清除、记录未确认、命中黑名单）时使用默认服。
- 未登录玩家会直接落在游戏服；KaLogin 仍会锁定位置并显示登录/注册界面。

## 工作流程

1. 玩家退服或切服时，后端 KaLogin 把坐标写入共享库 `kalogin_lastseen`（按 `updated_at` 单调，切服乱序不会写回旧位置）。
2. 玩家真正离开代理时，代理把其所在子服写入记录的 `server` 列；若该子服在 `blacklist` 中则删除整条记录。
3. 玩家下次连接时，代理异步读库：记录存在、`server` 非空、未命中黑名单且该子服已探活在线，则把初始服务器设为该子服。
4. 玩家在该子服完成登录/跨服恢复，认证完成后后端发送 `lastseen/ready`。
5. 记录服等于当前服（直连命中）时，代理只通知当前服传送坐标；不同（直连未命中，如记录服离线后恢复）时按跨服兜底切换。
6. 世界不存在或坐标越界时，目标服发送 `lastseen/abort`，代理把玩家回退到 `default-server`（未配置则停留在当前服）。
7. `/logout`、注销会删除该玩家的记录。

## 协议动作

后端 → 代理：

- `lastseen/ready`：uuid。
- `lastseen/abort`：uuid。

代理 → 目标后端：

- `lastseen/teleport`：uuid、世界、x、y、z、yaw、pitch。

> 坐标不再经代理中转：后端直接写库，代理只读。

## 前置条件

- 依赖 `modules.kalogin`（登录会话）以及后端 KaLogin 的 `proxy.enabled`、`last-seen.enabled`。
- 代理与所有后端须连接同一 MySQL 库（`database.*`），表 `kalogin_lastseen` 由后端建表。
- 后端须配置 `proxy.server-name`（与代理注册名一致）。
- 升级注意：已有安装升级后不会自动出现 `modules.lastseen` / `database`，需手动补充。

## 已知限制

- 玩家异常掉线且后端未触发退服事件时可能保留上一次的位置。
- 代理刚启动且尚未完成子服探活时，本会话暂不直连（回退默认服）。
- 黑名单只依据"离开时所在的子服"判断；玩家在 PVE/PVP 内部跨世界移动不会额外触发记录。
- 记录随玩家数增长；如需可定期清理长期未更新的行。
