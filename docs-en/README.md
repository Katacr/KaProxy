---
description: KaProxy - A Velocity and BungeeCord proxy for Ka plugins
---

# Home

> Use one proxy plugin to provide a consistent cross-server experience for KaGuilds and KaTpa.

**KaProxy** runs on your Velocity or BungeeCord proxy. It does not need to be installed on every Minecraft backend server. It allows Ka plugins to identify which backend a player is connected to and coordinates cross-server guild messages and teleport requests.

> **Requirements**
>
> - Java 21
> - Velocity 3.4 or BungeeCord 1.21
> - The corresponding Ka plugin installed on every backend that participates in a cross-server feature
> - All participating backends connected to the same proxy network

[中文文档](../docs/README.md)

***

## Main Features

### KaGuilds Compatibility

* Forward KaGuilds guild messages between backend servers
* Synchronize online players and their current backend
* Work with existing KaGuilds proxy settings without redesigning the guild system

### KaTpa Cross-Server Teleportation

* Support TPA and TPAHERE requests between players on different backends
* Coordinate acceptance, denial, cancellation, cooldowns, and timeouts
* Keep the normal warm-up experience on the source backend
* Move the traveler to the destination backend before the final teleport
* Optionally follow the target if they switch to another backend

### Two Proxy Platforms

The same `KaProxy-1.0.0.jar` supports:

* Velocity 3.4
* BungeeCord 1.21

***

## Quick Navigation

* [Getting Started](getting-started.md)
* [Configuration](configuration.md)
* [KaGuilds Module](modules/guilds.md)
* [KaTpa Module](modules/tpa.md)
* [Commands and Permissions](commands.md)
* [Upgrading and Migration](migration.md)
* [Troubleshooting](troubleshooting.md)

## Important Notes

* Install KaProxy on the proxy only. Do not place it in the `plugins` folder of a Paper, Folia, or Spigot backend.
* Do not run KaProxy and the old `KaGuildsProxy.jar` on the same proxy. Guild messages may otherwise be forwarded more than once.
* KaProxy does not replace KaGuilds or KaTpa. The corresponding plugin must still be installed and configured on each backend.
* After changing the configuration or language files, use `/kaproxy reload`.
