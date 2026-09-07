# KaMenu Cross-Server Action Forwarding

KaProxy lets KaMenu menu target selectors reach players on other backends.

## Requirements

* KaProxy's Kamenu module is enabled.
* Each participating backend has KaMenu installed with `kaproxy.enabled: true` in `config.yml`.
* All players connect through the same Velocity or BungeeCord proxy.

## Configuration

In KaProxy's `config.yml`:

```yaml
modules:
  kamenu:
    enabled: true
    # Backends allowed to receive cross-server actions.
    # Set to all or * to forward to all backends (excluding the source).
    # Servers not listed will not receive cross-server action packets.
    servers:
      - survival
      - creative
```

## Workflow

1. A player on backend A clicks a menu button with the `{player: selector}{cross}` tag.
2. KaMenu first executes the action locally for players matching the selector.
3. KaMenu encodes the action and sends it to KaProxy via the `kaproxy:main` channel.
4. KaProxy forwards the packet to target backends per the configured `servers` list (excluding the source).
5. Each target backend evaluates the selector locally and executes the action for matched players.

## Supported Actions

The cross-server whitelist includes only message, economy, and data actions:

* `tell`, `actionbar`, `title`, `sound`, `hovertext`
* `money`, `points`
* `data`, `gdata`, `tmpdata`, `meta`, `set-data`, `set-gdata`, `set-meta`

Non-whitelisted actions are rejected by the backend with a warning.

## Notes

* Selector conditions are evaluated **locally** on each backend; PAPI variables and KaMenu built-in predicates are fully available.
* When `servers` is set to `all` or `*`, packets are forwarded to all backends with connected players (excluding the source).
* If a target backend has no online players, it is naturally skipped.
* The proxy does not parse action content; it only routes packets. Security is enforced by the backend whitelist.
