# Commands and Permissions

KaProxy management commands run through the proxy console or proxy command system, not an individual backend console.

## Commands

### `/kaproxy status`

Displays:

* Whether the Guilds module is enabled
* Whether the Tpa module is enabled
* The current proxy player count

The proxy console and players can use this status command.

### `/kaproxy reload`

Reloads:

* `config.yml`
* The currently selected language file
* Guilds and Tpa module switches and settings

Permission:

```text
kaproxy.admin
```

The proxy console normally bypasses permission checks. Players need `kaproxy.admin` to reload KaProxy.

## Permission Recommendation

Only grant this permission to trusted administrators:

```text
kaproxy.admin
```

Regular players do not need KaProxy permissions. Player permissions for TPA and guild chat are managed by KaTpa and KaGuilds on the backend servers.

## Reload Notes

* Configuration and language changes can be reloaded without restarting the proxy.
* Disabling the Tpa module cancels active cross-server teleport requests.
* Restart the proxy after replacing the KaProxy JAR or changing the proxy version.
