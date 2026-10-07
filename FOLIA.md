# Paper / Folia compatibility — 1.2.0

Use `NordHomes-1.2.0.jar` on either platform with JDK 25. Entity/global scheduler APIs support both platforms; no separate branch is needed.

Countdowns and completion messages use the player's entity scheduler; teleportAsync handles cross-region travel. Shared YAML access is serialized, preserving the data format and 120-second delay. Persistence still uses synchronous YAML writes: this is compatibility work, not a demonstrated 1000-player storage optimization.

## Verification and limits

Run `mvn clean verify`. The [isolated integration harness](https://github.com/NetGraniz/NordChat/tree/main/test-support) boots all eight adapted plugins together with synthetic loopback clients, separated regions, local HTTPS and synthetic data. Movement cancellation dispatches an owning-region PlayerMoveEvent, not real-client movement. Never install the helper JAR on a real server. The harness records runtime results outside the repository. This is not a 1000-player load test, a production database test or a guarantee for future Minecraft versions.

## Updating

Back up configuration and player data, stop the server, replace only this plugin JAR without duplicates, and preserve installed data. Do not overwrite working config with repository templates. This release does not migrate worlds or production data.
