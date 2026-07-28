# KaGuilds Cross-Server Module

The KaProxy Guilds module forwards guild messages and synchronizes online players for KaGuilds installations on different backends.

## Requirements

* KaProxy is installed on Velocity or BungeeCord.
* KaGuilds is installed on every backend in the guild network.
* The KaGuilds proxy feature is enabled.
* All KaGuilds backends use the same MySQL database.

## Recommended Configuration

KaProxy:

```yaml
modules:
  guilds:
    enabled: true
    legacy-channel-enabled: true
    sync-player-list: true
```

KaGuilds backend:

```yaml
proxy: true
```

The exact setting location may vary between KaGuilds versions.

## How to Test

1. Join two different backends with two test players.
2. Make sure both players are in the same guild or otherwise meet your guild chat test requirements.
3. Send a guild message from one backend.
4. Confirm that the other backend receives it exactly once.

## When to Disable Player-List Synchronization

Consider setting `sync-player-list` to `false` only when:

* Your KaGuilds version does not need the proxy player list.
* Another explicitly compatible source already provides the online list.
* You are troubleshooting duplicated player-list updates.

For normal installations, keep the default value of `true`.
