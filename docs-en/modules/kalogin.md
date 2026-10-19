# KaLogin Cross-Server Session

KaProxy's `kalogin` module works with KaLogin backends so players who logged in on one backend stay logged in when switching to another.

## Boundary

- KaProxy owns the authoritative "logged-in session" (player UUID, name, login IP, creation time).
- KaLogin backends only report facts (login success, logout, unregister, password change) and query the session; they do not decide cross-server validity themselves.
- A session is destroyed when the player truly disconnects from the proxy; backend switches do not destroy it.
- All backends must share the same database (MySQL), otherwise account data is not consistent across servers.

## Configuration

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

| Option | Default | Description |
|--------|---------|-------------|
| `modules.kalogin.enabled` | `true` | Enable the KaLogin cross-server session. |
| `modules.kalogin.bind-ip` | `true` | Bind the session to the login IP; restore is rejected when the IP changes. |
| `modules.kalogin.debug` | `false` | Log session create/query/invalidate decisions for troubleshooting. |
| `modules.kalogin.security-alert.enabled` | `true` | Send a targeted security alert when someone tries to log into an already logged-in account. |
| `modules.kalogin.security-alert.cooldown-seconds` | `600` | Per-player alert cooldown (seconds) to prevent spam via repeated logins. |
| `modules.kalogin.ip-ban.enabled` | `true` | Temporarily ban a source IP after too many failed logins. |
| `modules.kalogin.ip-ban.window-seconds` | `600` | Failure counting window (seconds). |
| `modules.kalogin.ip-ban.threshold` | `10` | Ban the IP once failures in the window reach this count. |
| `modules.kalogin.ip-ban.duration-seconds` | `1800` | Temporary ban duration (seconds). |

## Flow

1. A player logs in or registers on backend A. KaLogin sends `kalogin/auth`, KaProxy stores the session and binds the login IP.
2. The player switches to backend B. B's KaLogin sends `kalogin/query` on join.
3. KaProxy validates the session and IP and replies `kalogin/session`:
   - Hit: B restores the login state and skips login/registration.
   - Miss or timeout: B falls back to local login/registration (`proxy.require-proxy` on the backend decides whether to reject).
4. When the player truly disconnects from the proxy, the session is destroyed; backend switches do not destroy it.
5. When the player runs `/logout`, KaLogin sends `kalogin/logout`; KaProxy invalidates the session and disconnects the player from the whole network.
6. Unregistering sends `kalogin/unregister`; a successful password change sends `kalogin/password_changed` (refreshing the session time).

## Protocol Actions

Backend to proxy:

- `kalogin/auth`: `uuid`, name, IP.
- `kalogin/query`: `uuid`, name, IP.
- `kalogin/logout`: `uuid`.
- `kalogin/unregister`: `uuid`, name.
- `kalogin/password_changed`: `uuid`.
- `kalogin/login_failed`: `uuid`, IP; the proxy counts failures per IP and temporarily bans the IP at the threshold.

Proxy to the querying backend:

- `kalogin/session`: `uuid`, authenticated flag, name, session creation time, login IP, reason (`ok` / `none` / `ip-mismatch`).

Proxy to the victim's backend:

- `kalogin/security_alert`: `uuid`, name, attacker IP; sent only to the victim's backend, which displays the alert.

## Security Alert

When an account already has an online session and another connection tries to log in, Velocity marks the new connection `CONFLICTING_LOGIN`. KaProxy uses this to send a targeted security alert to the still-online, logged-in player (by default: "another device is trying to log into your account"), rendered by KaLogin with the `proxy.security-alert` text.

- Only alerts when the victim is online and in a logged-in session.
- Per-player cooldown (`security-alert.cooldown-seconds`) prevents an attacker from spamming the victim via repeated conflict logins.
- When the rejected connection and the online session share the same source IP (the player's own reconnect), the alert is skipped.

## Temporary IP Ban

KaLogin reports `kalogin/login_failed` on each incorrect password. A proxy-rejected `CONFLICTING_LOGIN` also counts when the surviving connection is authenticated; conflicts against an unauthenticated placeholder are excluded to avoid counting a rejected legitimate player against their own IP. KaProxy counts failures per source IP within `window-seconds` and bans the IP for `duration-seconds` once the count reaches `threshold`; banned IPs are rejected at the `PreLoginEvent` stage.

## Requirements

- Every backend installs KaLogin and sets `proxy.enabled: true` in `config.yml`.
- Every backend shares the same MySQL database.
- In AuthMe mode, each backend's AuthMe must also share the same database.

## Upgrade Note

For existing installations, `modules.kalogin` is not added to `config.yml` automatically; add it manually. Without it the module stays disabled and KaLogin queries time out and fall back to local login.
