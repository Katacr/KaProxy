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
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `modules.kalogin.enabled` | `true` | 是否启用 KaLogin 跨服登录会话。 |
| `modules.kalogin.bind-ip` | `true` | 会话绑定登录时的 IP；来源 IP 变化时拒绝恢复登录态。 |
| `modules.kalogin.debug` | `false` | 记录会话建立、查询与失效的决策，用于排查链路问题。 |

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

代理 → 查询来源后端：

- `kalogin/session`：`uuid`、是否已认证、名称、会话建立时间、登录 IP、原因（`ok` / `none` / `ip-mismatch`）。

## 前置条件

- 所有子服安装 KaLogin，并在 `config.yml` 中设置 `proxy.enabled: true`。
- 所有子服共享同一 MySQL 数据库。
- 使用 AuthMe 模式时，各子服的 AuthMe 也需共享同一数据库。

## 升级注意

已有安装升级后，`config.yml` 不会自动出现 `modules.kalogin` 节点，需要手动补充；未补充时该模块按禁用处理（KaLogin 查询会超时并回退本地登录）。
