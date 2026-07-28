# Getting Started

This page explains how to install KaProxy on an existing Velocity or BungeeCord network.

## Before You Install

Make sure that:

* The proxy runs on Java 21.
* The proxy is Velocity 3.4 or BungeeCord 1.21.
* Players can already join the backend servers through the proxy.
* KaGuilds is installed on each backend that needs cross-server guild features.
* A compatible KaTpa version is installed on each backend that needs cross-server teleportation.

## Install KaProxy

1. Stop the proxy.
2. Place `KaProxy-1.0.0.jar` in the proxy's `plugins` folder.
3. If `KaGuildsProxy.jar` is currently installed, move it out of the `plugins` folder.
4. Start the proxy.
5. Wait for KaProxy to create its default configuration and language files.

The default data folder is usually:

| Platform | Data Folder |
|----------|-------------|
| Velocity | `plugins/kaproxy/` |
| BungeeCord | `plugins/KaProxy/` |

Some proxy implementations may use a different folder name. Use the folder that is created during startup.

## Configure Backend Plugins

### Using KaGuilds

On every backend that participates in the guild network:

1. Install KaGuilds.
2. Enable its proxy feature, for example with `proxy: true`.
3. Connect all KaGuilds backends to the same MySQL database.
4. Restart or reload KaGuilds on each backend.

### Using KaTpa

On every backend that participates in cross-server teleportation:

1. Install KaTpa.
2. Enable its proxy feature, for example with `proxy.enabled: true`.
3. We recommend using the same MySQL database on every backend so player settings and lists remain consistent.
4. Restart or reload KaTpa on each backend.

## Verify the Installation

Run this command in the proxy console:

```text
kaproxy status
```

It should display the Guilds and Tpa module states and the current proxy player count.

Then use two test accounts on different backends:

* Send a KaGuilds guild message and confirm that it reaches the other backend.
* Send a KaTpa request and confirm that accepting it moves the traveler to the target player.

If a feature does not work, see [Troubleshooting](troubleshooting.md).
