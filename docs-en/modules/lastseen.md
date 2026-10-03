# Last Server and Position

KaProxy's `lastseen` module works with KaLogin backends so players return to the server and coordinates where they last logged off, instead of always starting on the default server (for example, Lobby).

Positions live in a shared MySQL table `kalogin_lastseen` (created by the KaLogin backends), so proxy restarts and concurrent backends never lose data.

## Boundary

- **Coordinates** are written by the KaLogin backend directly into the shared table when a player leaves a backend (including server switches). This does not rely on a plugin-message carrier, so even the last player on a backend is recorded reliably.
- The **`server` column** is written by the proxy only when the player **truly disconnects** from the proxy (not on a switch). Only records with a non-empty `server` are proxy-confirmed leave positions.
- On first connection the proxy reads the table asynchronously to pick the initial server; after authentication completes (the backend sends `ready`) it delivers the coordinates.
- `/logout` and unregister delete the record.

## Configuration

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
    mode: direct
    join-delay-seconds: 5
    default-server: "lobby"
    blacklist: "pve,pvp"
    debug: false
```

| Option | Default | Description |
|--------|---------|-------------|
| `database.*` | - | Cross-server position database; must point at the same DB as every KaLogin backend (reuse `kalogin`). |
| `modules.lastseen.enabled` | `true` | Enable returning to the last server and position. |
| `modules.lastseen.mode` | `direct` | One of three modes, see below. |
| `modules.lastseen.join-delay-seconds` | `5` | `auto` only: delay before traveling after login (time for the resource pack). |
| `modules.lastseen.default-server` | `lobby` | Backend to fall back to when the target world is invalid; empty keeps the player on the current backend. |
| `modules.lastseen.blacklist` | empty | Backends whose positions are never stored (comma-separated, case-insensitive). |
| `modules.lastseen.debug` | `false` | Log position update, travel, and fallback decisions. |

## Modes

`modules.lastseen.mode` controls how a player returns to their last position:

- **`direct` (default)**: **straight to the last backend**. On first connection the proxy reads the DB and sets the initial server to the last backend, then teleports after login. No prompt, and the default server is skipped.
- **`manual`**: **prompt to confirm**. The player always lands on the proxy's default server (usually Lobby). After login the proxy sends `offer`; the backend waits `join-delay-seconds` and then shows a "return to last position?" confirmation (Confirm/Cancel).
  - **Confirm** → the backend replies `confirm`; the proxy reads the record, switches back, and delivers the coordinates.
  - **Cancel** → the backend replies `decline`; the record is **kept** and the player stays, so the prompt appears again next login.
- **`auto`**: **delayed auto travel**. The player lands on the default server (usually Lobby) as normal; after login the proxy waits `join-delay-seconds` and then reads the record, switches back, and teleports. No prompt. The delay lets the resource pack finish downloading on Lobby.

> The mode is configured on the proxy only; backends need no configuration. Both `manual` and `auto` pass through the default server first.

## Position Blacklist

Backends such as dungeons (PVE) or arenas (PVP) may only make sense during an event; the rest of the time the map is "dead" but the server keeps running. Storing a position on those backends would send the player back to a meaningless map on the next login.

`blacklist` prevents this:

- When the player **truly disconnects** from a blacklisted backend, the proxy deletes the record.
- The player therefore has no record on the next login and uses `default-server` (for example, Lobby).
- Matching is case-insensitive; `blacklist: "pve,pvp"` or one entry per line both work.

> This only affects "where to return after logging off there". Switching to PVE/PVP during an event is unaffected, as is normal play and switching inside that backend.

## Direct Initial Connect

With `connect-directly` enabled, the proxy reads the database asynchronously on the **first connection** and sets the initial server to the last backend (Velocity `PlayerChooseInitialServerEvent` + `EventTask.resumeWhenComplete`, Bungee `PostLoginEvent.registerIntent`/`completeIntent`). The player logs in on that backend directly.

This avoids the double join caused by "land on the default server (Lobby), then switch to the target": two `PlayerJoinEvent` sequences, two chunk bursts, and duplicated plugin listeners/database access.

- The database read happens during login; the proxy waits for it before entering a backend. On timeout/failure the default server is used.
- The target backend must be registered and confirmed online by ping; otherwise the default server is used (including right after proxy startup before the first probe completes).
- Without a stored position (first join, cleared record, unconfirmed record, or blacklisted), the default server is used.
- The unauthenticated player lands on a game backend; KaLogin still locks them and shows the login/registration UI.

## Flow

1. When the player leaves or switches a backend, the KaLogin backend writes the coordinates to the shared `kalogin_lastseen` table (monotonic by `updated_at`, so out-of-order switch events cannot restore an old position).
2. When the player truly disconnects from the proxy, the proxy writes the current backend into the record's `server` column; if that backend is in `blacklist`, the whole record is deleted.
3. On the next connection:
   - **direct**: the proxy reads the record asynchronously; if it exists, `server` is non-empty, it is not blacklisted, and that backend is confirmed online, it becomes the initial server.
   - **manual / auto**: the player always lands on the default server.
4. The player logs in or restores the session, then sends `lastseen/ready`:
   - **direct**: the proxy reads the record and travels immediately (the initial backend is already the target, so usually only the coordinates are delivered).
   - **manual**: the proxy reads the record and sends `offer`; the backend prompts after the delay, and on Confirm replies `confirm` so the proxy travels.
   - **auto**: the proxy waits `join-delay-seconds` and then reads the record and travels (re-resolving the online player when the delay elapses).
5. If the world does not exist or the coordinates are out of range, the backend sends `lastseen/abort` and the proxy moves the player to `default-server` (or keeps them if not configured).
6. `/logout` and unregister delete the record.

## Protocol Actions

Backend to proxy:

- `lastseen/ready`: uuid.
- `lastseen/abort`: uuid.
- `lastseen/confirm`: uuid (manual mode, player chose to return).
- `lastseen/decline`: uuid (manual mode, player chose not to return).

Proxy to target backend:

- `lastseen/teleport`: uuid, world, x, y, z, yaw, pitch.
- `lastseen/offer`: uuid, delay seconds, target backend name (manual mode; the backend prompts on it).

> Coordinates no longer travel through the proxy: backends write them straight to the database and the proxy only reads.

## Requirements

- Depends on `modules.kalogin` (login session) and KaLogin's `proxy.enabled` and `last-seen.enabled`.
- The proxy and every backend must use the same MySQL database (`database.*`); the `kalogin_lastseen` table is created by the backends.
- Backends must set `proxy.server-name` matching the proxy's registered server name.
- Upgrade note: existing installations must add `modules.lastseen` and `database` manually.

## Known Limitations

- After an abnormal disconnect where the backend fires no quit event, the previous position may be kept.
- Right after proxy startup, before the first server probe completes, direct connect is skipped for that session (default server is used).
- The blacklist only considers the backend the player was on when leaving; moving between worlds inside PVE/PVP does not add extra records.
- Records grow with the player count; prune long-unupdated rows periodically if needed.
