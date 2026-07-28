# KaTpa Cross-Server Teleportation

KaProxy allows players on different backends to continue using familiar TPA and TPAHERE flows.

## Requirements

* The KaProxy Tpa module is enabled.
* A compatible KaTpa version is installed on every participating backend.
* The KaTpa proxy feature is enabled on each backend, for example with `proxy.enabled: true`.
* All players connect through the same Velocity or BungeeCord proxy.

We recommend using the same MySQL database for KaTpa on every backend so player settings, ignore lists, and other saved data remain consistent.

## Player Flow

### TPA

1. Player A sends a TPA request to Player B on another backend.
2. Player B accepts the request.
3. Player A completes the warm-up on the current backend.
4. The proxy moves Player A to Player B's backend.
5. The destination backend teleports Player A to Player B.

### TPAHERE

1. Player A invites Player B on another backend to teleport to them.
2. Player B accepts and completes the warm-up.
3. The proxy moves Player B to Player A's backend.
4. The destination backend teleports Player B to Player A.

The actual commands, messages, and warm-up rules are controlled by KaTpa on the backend servers.

## When the Target Switches Servers

```yaml
modules:
  tpa:
    follow-target-server: true
```

When enabled, the traveler attempts to follow the target to their new backend if the target switches before the teleport is complete.

When disabled, leaving the originally selected backend cancels the transaction. Use this mode if the destination should remain fixed throughout a request.

## Timeouts and Cooldowns

KaProxy limits request lifetimes and cooldowns submitted by backend servers so different backends cannot use excessively different values.

* Pending requests use `request-timeout-min-seconds` and `request-timeout-max-seconds`.
* After acceptance, warm-up, backend switching, and arrival share `transaction-timeout-seconds`.
* Cross-server request cooldowns cannot exceed `cooldown-max-seconds`.

The proxy limits should usually be equal to or slightly higher than the corresponding KaTpa settings on each backend.

## Common Cancellation Reasons

* The requester or target leaves the proxy.
* The request is not handled before it expires.
* Movement, damage, or another backend rule interrupts the warm-up.
* The destination backend is unavailable.
* The target switches backends while `follow-target-server` is disabled.
* The accepted request does not finish before the transaction timeout.
* An administrator reloads the configuration and disables the Tpa module.

These cases do not leave permanent requests. Players can send a new request after the problem is resolved.
