# Troubleshooting

## Quick Checklist

When a cross-server feature does not work, check the following in order:

1. KaProxy is installed on the proxy, not a backend server.
2. The proxy runs on Java 21.
3. The startup log shows that KaProxy loaded successfully.
4. `/kaproxy status` reports the required module as enabled.
5. KaGuilds or KaTpa is installed and its proxy feature is enabled on every relevant backend.
6. The test players are online through the same proxy.
7. `/kaproxy reload` was run after configuration changes.

## Common Symptoms

### No KaProxy Data Folder Is Created

* Make sure the JAR is in the proxy's `plugins` folder.
* Make sure the proxy uses Java 21.
* Check the proxy startup log for plugin loading errors.
* Velocity normally uses `plugins/kaproxy/`; BungeeCord normally uses `plugins/KaProxy/`.

### Guild Messages Do Not Cross Servers

* Make sure `modules.guilds.enabled` and `legacy-channel-enabled` are `true`.
* Make sure the KaGuilds proxy option is enabled on each backend.
* Confirm that all KaGuilds backends use the same database.
* Confirm that the sender and receiver are on the same proxy network.

### TPA Only Works on the Same Backend

* Make sure `modules.tpa.enabled` is `true`.
* Make sure `proxy.enabled` is `true` for KaTpa on every backend.
* Confirm that both players are online through the same proxy.
* Confirm that every backend uses a compatible KaTpa version.

### Accepting a Request Does Not Switch Servers

* Confirm that the destination backend exists in the proxy configuration and is online.
* Make sure the traveler completes the warm-up configured by KaTpa.
* Check whether movement, damage, or another backend rule cancels the warm-up.
* Try increasing `transaction-timeout-seconds` before testing again.

### The Target Moved or the Request Is Stale

If the target changes backends during the teleport:

* Set `follow-target-server` to `true` if the traveler should follow them.
* Keep it `false` if the original destination should remain fixed; the player must send a new request.

Expired requests, disconnected players, and repeated actions can also make an older request stale.

### Language Changes Do Not Apply

* Make sure `core.language` exactly matches the file name in `lang`, without `.yml`.
* Save the language file as UTF-8.
* Run `/kaproxy reload`.

### Reload Fails

* Use spaces for indentation in `config.yml`.
* Use only `true` or `false` for boolean settings.
* Enter time settings as whole numbers in seconds.
* Restore the latest working backup and reload again.

## Information to Include in a Support Request

We recommend including:

* Proxy type and version
* Java version
* KaProxy, KaGuilds, and KaTpa versions
* Output from `/kaproxy status`
* Relevant configuration with IP addresses, passwords, and database details removed
* Proxy and backend logs from immediately before and after the issue

Never publish database passwords, proxy forwarding secrets, or private player information.
