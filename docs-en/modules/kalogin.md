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
```

| Option | Default | Description |
|--------|---------|-------------|
| `modules.kalogin.enabled` | `true` | Enable the KaLogin cross-server session. |
| `modules.kalogin.bind-ip` | `true` | Bind the session to the login IP; restore is rejected when the IP changes. |
| `modules.kalogin.debug` | `false` | Log session create/query/invalidate decisions for troubleshooting. |

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

Proxy to the querying backend:

- `kalogin/session`: `uuid`, authenticated flag, name, session creation time, login IP, reason (`ok` / `none` / `ip-mismatch`).

## Requirements

- Every backend installs KaLogin and sets `proxy.enabled: true` in `config.yml`.
- Every backend shares the same MySQL database.
- In AuthMe mode, each backend's AuthMe must also share the same database.

## Upgrade Note

For existing installations, `modules.kalogin` is not added to `config.yml` automatically; add it manually. Without it the module stays disabled and KaLogin queries time out and fall back to local login.
