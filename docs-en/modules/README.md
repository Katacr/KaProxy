# Modules

KaProxy currently provides five user-facing modules that can be enabled independently.

## KaGuilds

Provides cross-server messages and online-player synchronization for KaGuilds installations on different backends.

[Read the KaGuilds module guide](guilds.md)

## KaTpa

Coordinates requests, warm-ups, backend switching, and final teleportation between players on different backends.

[Read the KaTpa module guide](tpa.md)

## Back

Coordinates KaTpa's `/back` and `/dback` cross-server return, managing backend switching and arrival delivery.

[Read the Back module guide](back.md)

## KaMenu

Provides cross-server action forwarding so KaMenu target selectors like `{player: *}{cross}` can reach players on other backends.

[Read the KaMenu module guide](kamenu.md)

## KaBroadcast

Forwards group announcements, real proxy-network join/quit events, and cross-server death events to KaBroadcast backends.

[Read the KaBroadcast module guide](broadcast.md)

If you do not use one of these features, disable its module in `config.yml`.
