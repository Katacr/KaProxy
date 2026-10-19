# KaLogin 跨服登录会话

KaProxy 的 `kalogin` 模块与 KaLogin 后端配合，让玩家在一个子服登录成功后，切换到其它子服时无需重复登录。

## 职责边界

- KaProxy 持有权威的“已登录会话”（玩家 UUID、名称、登录 IP、建立时间）。
- KaLogin 后端只上报事实（登录成功、登出、注销、改密）并查询会话，不自行判定跨服会话是否有效。
- 玩家真正断开代理时会话被销毁；子服之间切换不会销毁会话。
- 要求所有子服共享同一数据库（MySQL），否则账号数据无法跨服一致。

## 配置

```yaml
modules:
  kalogin:
    enabled: true
    bind-ip: true
    debug: false
    security-alert:
      enabled: true
      cooldown-seconds: 600
    ip-ban:
      enabled: true
      window-seconds: 600
      threshold: 10
      duration-seconds: 1800
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `modules.kalogin.enabled` | `true` | 是否启用 KaLogin 跨服登录会话。 |
| `modules.kalogin.bind-ip` | `true` | 会话绑定登录时的 IP；来源 IP 变化时拒绝恢复登录态。 |
| `modules.kalogin.debug` | `false` | 记录会话建立、查询与失效的决策，用于排查链路问题。 |
| `modules.kalogin.security-alert.enabled` | `true` | 他人尝试登录已登录账户时，向该玩家定向发送安全告警。 |
| `modules.kalogin.security-alert.cooldown-seconds` | `600` | 同一玩家的告警冷却（秒），防止被恶意反复登录刷屏。 |
| `modules.kalogin.ip-ban.enabled` | `true` | 登录失败次数过多时是否临时封禁来源 IP。 |
| `modules.kalogin.ip-ban.window-seconds` | `600` | 登录失败统计窗口（秒）。 |
| `modules.kalogin.ip-ban.threshold` | `10` | 窗口内失败达到该次数即封禁该 IP。 |
| `modules.kalogin.ip-ban.duration-seconds` | `1800` | 临时封禁时长（秒）。 |

## 工作流程

1. 玩家在子服 A 登录/注册成功，KaLogin 发送 `kalogin/auth`，KaProxy 登记会话并绑定登录 IP。
2. 玩家切换到子服 B，B 的 KaLogin 进服后发送 `kalogin/query`。
3. KaProxy 校验会话与 IP，回复 `kalogin/session`：
   - 命中：B 恢复登录态，跳过登录/注册。
   - 未命中或超时：B 回退本地登录/注册（是否拒绝进入由后端 `proxy.require-proxy` 决定）。
4. 玩家真正退出代理，KaProxy 销毁会话；子服切换不销毁。
5. 玩家执行 `/logout`，KaLogin 发送 `kalogin/logout`，KaProxy 使会话失效并把玩家断开整个群组。
6. 注销账户发送 `kalogin/unregister`；改密成功发送 `kalogin/password_changed`（刷新会话时间）。

## 协议动作

后端 → 代理：

- `kalogin/auth`：`uuid`、名称、IP。
- `kalogin/query`：`uuid`、名称、IP。
- `kalogin/logout`：`uuid`。
- `kalogin/unregister`：`uuid`、名称。
- `kalogin/password_changed`：`uuid`。
- `kalogin/login_failed`：`uuid`、IP；代理按 IP 累计失败，达阈值临时封禁。

代理 → 查询来源后端：

- `kalogin/session`：`uuid`、是否已认证、名称、会话建立时间、登录 IP、原因（`ok` / `none` / `ip-mismatch`）。

代理 → 受害者所在后端：

- `kalogin/security_alert`：`uuid`、名称、攻击者 IP；仅定向发往受害玩家所在后端，由该后端提示。

## 安全告警

当同一账户已有在线会话、又有新的连接尝试登录时，Velocity 会把新连接判为 `CONFLICTING_LOGIN`。KaProxy 借此向仍在线且已登录的玩家定向发送安全告警（默认提示"其他设备正在尝试登录您的账户"），由 KaLogin 用 `proxy.security-alert` 文案渲染展示。

- 仅当受害玩家在线且处于已登录会话时才告警。
- 按玩家冷却（`security-alert.cooldown-seconds`），防止攻击者反复制造冲突登录把受害者刷屏。
- 被拒连接与在线会话来源 IP 相同时视为玩家自身重连，跳过告警。

## 登录失败临时封禁

KaLogin 每次密码错误会向代理上报 `kalogin/login_failed`；当冲突登录的幸存连接已认证时，被拒连接也计为一次失败（未认证占位连接场景不计数，避免误封被拒的正版玩家）。KaProxy 按来源 IP 在 `window-seconds` 窗口内累计失败次数，达到 `threshold` 即临时封禁该 IP `duration-seconds`；被封 IP 的后续连接在 `PreLoginEvent` 阶段被拒绝。

## 前置条件

- 所有子服安装 KaLogin，并在 `config.yml` 中设置 `proxy.enabled: true`。
- 所有子服共享同一 MySQL 数据库。
- 使用 AuthMe 模式时，各子服的 AuthMe 也需共享同一数据库。

## 升级注意

已有安装升级后，`config.yml` 不会自动出现 `modules.kalogin` 节点，需要手动补充；未补充时该模块按禁用处理（KaLogin 查询会超时并回退本地登录）。
