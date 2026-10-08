# NordHomes 1.2.0

One home and a return to the last death location for Paper 26.2 and Folia 26.2. Both platforms use one JAR on Java 25.

## Commands

- `/sethome` saves your current location, replacing your previous home.
- `/home` starts a 120-second stationary countdown before teleporting home.
- `/back` starts the same countdown before returning to your last death location.

The action bar shows the remaining time. Moving, dying, disconnecting or stopping the plugin cancels the countdown. Looking around does not count as movement.

## Permissions

| Permission | Allows | Default |
| --- | --- | --- |
| `nordhomes.sethome` | `/sethome` | Everyone |
| `nordhomes.home` | `/home` | Everyone |
| `nordhomes.back` | `/back` | Everyone |

## Data and migration

The bundled `homes.yml` is empty. Installed homes are not overwritten; keep the server's file private and back it up before updating.

On the first migration, NordHomes can import a local HuskHomes database. It keeps one home per player, preferring the home named `home`. Keep a backup of the original database.

## Build and installation

Use Maven 3.9+ and JDK 25. See [BUILDING.md](BUILDING.md) for the release build and [FOLIA.md](FOLIA.md) for platform support. Stop the server before replacing the JAR, and retain existing player data.
