# KaBroadcast Group Events

KaProxy's `broadcast` module provides KaBroadcast backends with group announcements, unified join/quit messages, and cross-server death messages.

## Responsibility Boundary

- KaProxy determines real entry to and exit from the proxy network, validates the protocol, and forwards structured content.
- KaProxy does not store join, quit, announcement, or death templates.
- Each KaBroadcast backend renders the final message from its own configuration, allowing different languages and styles per backend.
- Every backend participating in group messages should install and enable KaBroadcast.

## Configuration

```yaml
modules:
  broadcast:
    enabled: true
    announcements-enabled: true
    connection-messages-enabled: true
    death-messages-enabled: true
    servers:
      - all
```

`all` or `*` forwards to every backend. When a list is configured, only the listed backends receive the events.

## Join, Quit and Server Switches

KaProxy tracks each online player's current backend:

1. The first backend connection emits one `player_join` event.
2. Switching between backends sends a `switch_request` to the destination backend to obtain its rendered result, then forwards `player_switch`; neither a join nor a quit event is emitted.
3. A real proxy disconnect emits one `player_quit` event.

Switch detection uses platform events (Velocity `ServerPostConnectEvent.getPreviousServer()`), and the backend's `modules.connection.switch-message-enabled` decides whether it is displayed.

The KaBroadcast backend suppresses Bukkit's native join and quit messages and displays the proxy event using its local template.

## Targeted Announcements

A backend can request that an announcement be sent to a single player via `broadcast/publish_targeted`. KaProxy resolves the target player by name and sends `broadcast/announcement_targeted` only to that player's backend, which renders and delivers it to the target with the Bukkit API.

## Death Messages

The KaBroadcast backend reads Bukkit's final death text, suppresses the local native death message, and sends a `death` event. KaProxy adds the source backend and forwards it as `player_death`; each backend renders it with its own death template.

Every public event includes a UUID `eventId`. The proxy and backends keep short-lived deduplication state so one event is not displayed repeatedly.

## Protocol Actions

Backend to proxy:

- `broadcast/publish`: announcement with `eventId`, sender, and message.
- `broadcast/publish_targeted`: targeted announcement with `eventId`, target player name, sender, and message.
- `broadcast/death`: death event with `eventId`, player UUID, player name, plain-text fallback, and component JSON.
- `broadcast/switch_publish`: the switch result rendered by the destination backend.

Proxy to backend:

- `broadcast/announcement`
- `broadcast/announcement_targeted`: targeted announcement, sent only to the target player's backend.
- `broadcast/player_join`
- `broadcast/player_switch`
- `broadcast/player_quit`
- `broadcast/player_death`: carries both the plain-text fallback and component JSON; old backends without component JSON are converted to a plain text component by the proxy.
- `broadcast/switch_request`: asks the destination backend for its rendered switch result.
