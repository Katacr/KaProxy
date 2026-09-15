# Configuration

The main KaProxy configuration is `config.yml` in the plugin data folder.

After editing it, run:

```text
kaproxy reload
```

## Default Configuration

```yaml
core:
  language: zh_CN
  log-unknown-modules: false

modules:
  guilds:
    enabled: true
    legacy-channel-enabled: true
    sync-player-list: true

  tpa:
    enabled: true
    request-timeout-min-seconds: 5
    request-timeout-max-seconds: 120
    transaction-timeout-seconds: 60
    cooldown-max-seconds: 3600
    follow-target-server: true

  back:
    enabled: true
    transaction-timeout-seconds: 30

  broadcast:
    enabled: true
    announcements-enabled: true
    connection-messages-enabled: true
    death-messages-enabled: true
    servers:
      - all
```

## General Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `core.language` | `zh_CN` | The language file name in the `lang` folder, without `.yml`. |
| `core.log-unknown-modules` | `false` | Logs unrecognized extension messages. Leave this disabled unless troubleshooting. |

## Guilds Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `modules.guilds.enabled` | `true` | Enables KaGuilds cross-server compatibility. |
| `modules.guilds.legacy-channel-enabled` | `true` | Keeps compatibility with existing KaGuilds proxy communication. Existing networks should leave this enabled. |
| `modules.guilds.sync-player-list` | `true` | Synchronizes online players and their current backends. |

## Tpa Settings

| Setting | Default | Accepted Range | Description |
|---------|---------|----------------|-------------|
| `modules.tpa.enabled` | `true` | `true` / `false` | Enables cross-server teleportation. Disabling it cancels active cross-server requests. |
| `request-timeout-min-seconds` | `5` | 1–3600 seconds | Shortest request lifetime a backend may submit. |
| `request-timeout-max-seconds` | `120` | Minimum value–86400 seconds | Longest request lifetime a backend may submit. |
| `transaction-timeout-seconds` | `60` | 10–600 seconds | Total time allowed for warm-up, server switching, and arrival after acceptance. |
| `cooldown-max-seconds` | `3600` | 0–86400 seconds | Maximum cross-server request cooldown accepted by the proxy. `0` prevents an additional cooldown. |
| `follow-target-server` | `true` | `true` / `false` | Whether the traveler follows the target when the target switches backends. |

Values outside the accepted range are limited to a valid value. Invalid numbers use their defaults.

## Back Settings

| Setting | Default | Accepted Range | Description |
|---------|---------|----------------|-------------|
| `modules.back.enabled` | `true` | `true` / `false` | Enables cross-server return (/back and /dback). Disabling it cancels active return transactions. |
| `transaction-timeout-seconds` | `30` | 5–600 seconds | Total time allowed for server switching and arrival teleport. |

## Kamenu Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `modules.kamenu.enabled` | `false` | Enables KaMenu cross-server action forwarding. |
| `modules.kamenu.servers` | — | List of backends allowed to receive cross-server actions. Set to `all` or `*` to forward to all backends (excluding the source). |

## Broadcast Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `modules.broadcast.enabled` | `true` | Enables KaBroadcast group event forwarding. |
| `modules.broadcast.announcements-enabled` | `true` | Allows backends to send and receive group announcements. |
| `modules.broadcast.connection-messages-enabled` | `true` | Forwards real proxy-network entry and exit events. Backend switches do not emit events. |
| `modules.broadcast.death-messages-enabled` | `true` | Forwards cross-server death events. |
| `modules.broadcast.servers` | `all` | Backends receiving events. `all` or `*` means every backend. |

KaProxy only forwards structured content and does not store message templates. Each KaBroadcast backend configures its own join, quit, announcement, and death text.

## Language Files

Language files are stored at:

```text
lang/zh_CN.yml
lang/en_US.yml
```

To use English:

```yaml
core:
  language: en_US
```

You can copy either file to create a custom language, such as `lang/my_lang.yml`, and set `core.language` to `my_lang`. Run `/kaproxy reload` after making changes.
