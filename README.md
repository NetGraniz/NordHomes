# NordHomes

Small Paper plugin providing only the Nord Fjell home features that are in use:

- `/sethome` saves or overwrites the player's single home.
- `/home` teleports to that home after the player stands still for 120 seconds.
- `/back` returns to the most recent death location after the same stationary delay.

Moving, being teleported, dying, disconnecting or stopping the plugin cancels a pending
teleport. Looking around does not count as movement. The remaining time is shown in the
action bar.

The bundled initial `homes.yml` is an empty template and contains no player locations.
Runtime player data must never be committed. Existing installed `homes.yml` files are
not replaced by the template. If a local HuskHomes database is present, the plugin can
import it on first migration. Named homes are collapsed to one location per player,
preferring the old home named `home`. Keep a private backup before migration.
