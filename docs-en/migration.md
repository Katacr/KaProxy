# Upgrading and Migration

## Upgrading KaProxy

1. Stop the proxy.
2. Back up `config.yml` and the `lang/` folder from the KaProxy data directory.
3. Move the old KaProxy JAR out of the `plugins` folder.
4. Install the new KaProxy JAR.
5. Start the proxy and review the startup log.
6. Run `/kaproxy status` to check the module states.
7. Use two test accounts to verify cross-server guild messages and teleportation.

You normally do not need to delete the existing configuration. If a new release adds settings, copy the relevant entries from the new default configuration.

## Migrating from KaGuildsProxy

KaProxy provides the compatibility features required by existing KaGuilds installations.

Migration steps:

1. Stop the proxy.
2. Remove or move `KaGuildsProxy.jar` from the proxy's `plugins` folder.
3. Install KaProxy.
4. Keep KaGuilds and its existing `proxy: true` setting on each backend.
5. Confirm that all backends still use the same MySQL database.
6. Start the proxy and test guild chat and cross-server online status.

You may keep a backup of the old plugin, but the old JAR must not remain in the active proxy `plugins` folder.

## Moving from Single-Server KaTpa to Cross-Server Use

1. Install KaProxy on the proxy and enable its Tpa module.
2. Install the same compatible KaTpa version on every participating backend.
3. Enable the KaTpa proxy option on each backend.
4. We recommend moving every backend to the same MySQL database.
5. Align the request timeout, warm-up, and cooldown settings across backends.
6. Test both TPA and TPAHERE between different backends.

## Rolling Back

To roll back:

1. Stop the proxy.
2. Restore the previous JAR and its matching configuration backup.
3. Make sure only one plugin is responsible for KaGuilds proxy forwarding.
4. Start the proxy and test the features again.

Restarting the proxy clears unfinished cross-server teleport requests. Schedule upgrades and rollbacks during a quiet period when possible.
