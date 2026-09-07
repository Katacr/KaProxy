# Back Cross-Server Return

The KaProxy Back module enables KaTpa's `/back` and `/dback` to return to precise coordinates on another backend.

## Requirements

* The KaProxy Back module is enabled.
* A compatible KaTpa version is installed on every participating backend.
* The KaTpa proxy feature is enabled on each backend, for example with `proxy.enabled: true`.
* All players connect through the same Velocity or BungeeCord proxy.

## Flow

1. The player runs `/back` or `/dback` on backend B.
2. KaTpa reads the previous or death location and finds the target is on backend A.
3. Backend B sends a return request to KaProxy via the `kaproxy:main` channel, carrying the target backend name and exact coordinates.
4. KaProxy switches the player to backend A.
5. After the player arrives at backend A, KaProxy delivers the coordinate data to backend A.
6. KaTpa on backend A teleports the player to the exact location.
7. Backend A notifies KaProxy that the teleport is complete, ending the transaction.

## Configuration

```yaml
modules:
  back:
    enabled: true
    transaction-timeout-seconds: 30
```

| Setting | Default | Accepted Range | Description |
|---------|---------|----------------|-------------|
| `enabled` | `true` | `true` / `false` | Enables cross-server return. Disabling it cancels active return transactions. |
| `transaction-timeout-seconds` | `30` | 5–600 seconds | Total time allowed for server switching and arrival teleport. The transaction is automatically canceled and the player is notified on timeout. |

## Common Failure Reasons

* The target backend is unavailable or does not exist.
* The player leaves the proxy during the server switch.
* The target world has been unloaded.
* The server switch and arrival teleport do not complete before the transaction timeout.
* An administrator reloads the configuration and disables the Back module.

These cases do not leave residual state. Players can run `/back` or `/dback` again after the issue is resolved.
