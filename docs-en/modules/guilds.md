# KaGuilds Compatibility

KaProxy can replace the old KaGuilds proxy plugin. It forwards guild messages and synchronizes online players for KaGuilds installations on different backends.

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

## Migrating from the Old Proxy Plugin

If `KaGuildsProxy.jar` is currently installed on the proxy:

1. Stop the proxy.
2. Move the old JAR out of the proxy's `plugins` folder.
3. Install the KaProxy JAR.
4. Keep the existing KaGuilds proxy settings on each backend.
5. Start the proxy and test cross-server guild messages.

Do not run the old proxy plugin and KaProxy together. The same message may otherwise be forwarded more than once.

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
