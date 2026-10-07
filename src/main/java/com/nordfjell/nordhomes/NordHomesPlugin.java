package com.nordfjell.nordhomes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class NordHomesPlugin extends JavaPlugin implements Listener {
    private static final int TELEPORT_DELAY_SECONDS = 120;

    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create the NordHomes data directory.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "homes.yml");
        if (!dataFile.exists()) saveResource("homes.yml", false);
        data = YamlConfiguration.loadConfiguration(dataFile);
        migrateNamedHomes();
        importLegacyHuskHomes();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NordHomes enabled with migrated HuskHomes locations.");
    }

    @Override
    public void onDisable() {
        pendingTeleports.values().forEach(pending -> { if(pending.task!=null)pending.task.cancel(); });
        pendingTeleports.clear();
        saveData();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        cancelPending(player, "Teleport cancelled because you died.");
        writeLocation(root(player.getUniqueId()) + ".last-death", player.getLocation());
        saveData();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!pendingTeleports.containsKey(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()
                || from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {
            cancelPending(event.getPlayer(), "Teleport cancelled because you moved.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelPending(event.getPlayer(), null);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(error("Only players can use this command."));
            return true;
        }
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> setHome(player, args);
            case "home" -> goHome(player, args);
            case "back" -> goBack(player, args);
            default -> false;
        };
    }

    private boolean setHome(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(error("Usage: /sethome"));
            return true;
        }
        cancelPending(player, null);
        String path = homePath(player.getUniqueId());
        boolean replaced;
        synchronized(this){replaced = data.isConfigurationSection(path);}
        writeLocation(path, player.getLocation());
        saveData();
        player.sendMessage(success(replaced ? "Home updated." : "Home saved."));
        return true;
    }

    private boolean goHome(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(error("Usage: /home"));
            return true;
        }
        Location target = readLocation(homePath(player.getUniqueId()));
        if (target == null) {
            player.sendMessage(error("No home has been saved yet."));
            return true;
        }
        beginTeleport(player, target, "home", "Teleported home.");
        return true;
    }

    private boolean goBack(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(error("Usage: /back"));
            return true;
        }
        Location target = readLocation(root(player.getUniqueId()) + ".last-death");
        if (target == null) {
            player.sendMessage(error("No death location has been saved yet."));
            return true;
        }
        beginTeleport(player, target, "death location", "Returned to your last death location.");
        return true;
    }

    private void beginTeleport(Player player, Location target, String destinationName, String successMessage) {
        cancelPending(player, null);
        UUID playerId = player.getUniqueId();
        PendingTeleport pending = new PendingTeleport(target.clone(), destinationName, successMessage,
                TELEPORT_DELAY_SECONDS);
        pendingTeleports.put(playerId, pending);
        pending.task = player.getScheduler().runAtFixedRate(this, task -> {
            if (!player.isOnline() || pendingTeleports.get(playerId) != pending) {
                task.cancel();
                return;
            }
            pending.secondsRemaining--;
            if (pending.secondsRemaining <= 0) {
                pendingTeleports.remove(playerId);
                task.cancel();
                teleport(player, pending.target, pending.successMessage);
                return;
            }
            showCountdown(player, pending.destinationName, pending.secondsRemaining);
        }, () -> pendingTeleports.remove(playerId,pending), 20L, 20L);
        if (pending.task == null) { pendingTeleports.remove(playerId,pending); return; }
        player.sendMessage(Component.text("Teleporting to your " + destinationName + " in "
                + TELEPORT_DELAY_SECONDS + " seconds. Do not move.", NamedTextColor.YELLOW));
        showCountdown(player, destinationName, TELEPORT_DELAY_SECONDS);
    }

    private void showCountdown(Player player, String destinationName, int seconds) {
        player.sendActionBar(Component.text(capitalize(destinationName) + " in " + seconds
                + "s · do not move", NamedTextColor.GOLD));
    }

    private void cancelPending(Player player, String message) {
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending == null) return;
        pending.task.cancel();
        player.sendActionBar(Component.empty());
        if (message != null) player.sendMessage(error(message));
    }

    private void teleport(Player player, Location target, String successMessage) {
        player.sendMessage(Component.text("Loading destination…", NamedTextColor.GRAY));
        player.teleportAsync(target).whenComplete((teleported, error) -> {
            if (!isEnabled()) return;
            try { player.getScheduler().execute(this, () -> {
                    if (!player.isOnline()) return;
                    if (error != null || !Boolean.TRUE.equals(teleported)) {
                        player.sendMessage(error("Teleport failed. Please try again."));
                    } else {
                        player.sendMessage(success(successMessage));
                    }
                },null,1L); } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
        });
    }

    private synchronized void writeLocation(String path, Location location) {
        World world = location.getWorld();
        data.set(path + ".world", world.getName());
        data.set(path + ".world-uuid", world.getUID().toString());
        data.set(path + ".x", location.getX());
        data.set(path + ".y", location.getY());
        data.set(path + ".z", location.getZ());
        data.set(path + ".yaw", location.getYaw());
        data.set(path + ".pitch", location.getPitch());
    }

    private synchronized Location readLocation(String path) {
        if (!data.isConfigurationSection(path)) return null;
        World world = null;
        String uuidValue = data.getString(path + ".world-uuid");
        if (uuidValue != null) {
            try {
                world = Bukkit.getWorld(UUID.fromString(uuidValue));
            } catch (IllegalArgumentException ignored) {
                // Fall back to the stable world name below.
            }
        }
        if (world == null) world = Bukkit.getWorld(data.getString(path + ".world", ""));
        if (world == null) return null;
        return new Location(world,
                data.getDouble(path + ".x"),
                data.getDouble(path + ".y"),
                data.getDouble(path + ".z"),
                (float) data.getDouble(path + ".yaw"),
                (float) data.getDouble(path + ".pitch"));
    }

    private String root(UUID playerId) {
        return "players." + playerId;
    }

    private String homePath(UUID playerId) {
        return root(playerId) + ".home";
    }

    private synchronized void saveData() {
        if(data==null || dataFile==null)return;
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save homes.yml", exception);
        }
    }

    private void importLegacyHuskHomes() {
        if (data.getBoolean("migration.huskhomes-imported", false)) return;
        File legacyDatabase = new File(getDataFolder().getParentFile(), "HuskHomes/HuskHomesData.db");
        if (!legacyDatabase.isFile()) return;

        String query = "select h.owner_uuid,s.name,p.world_name,p.world_uuid,p.x,p.y,p.z,p.yaw,p.pitch "
                + "from huskhomes_homes h "
                + "join huskhomes_saved_positions s on s.id=h.saved_position_id "
                + "join huskhomes_position_data p on p.id=s.position_id "
                + "order by h.owner_uuid, case when lower(s.name)='home' then 0 else 1 end, s.name";
        int imported = 0;
        Set<UUID> importedPlayers = new HashSet<>();
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + legacyDatabase.getAbsolutePath());
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(query)) {
                while (rows.next()) {
                    UUID owner = UUID.fromString(rows.getString("owner_uuid"));
                    if (!importedPlayers.add(owner) || data.isConfigurationSection(homePath(owner))) continue;
                    String path = homePath(owner);
                    data.set(path + ".world", rows.getString("world_name"));
                    data.set(path + ".world-uuid", rows.getString("world_uuid"));
                    data.set(path + ".x", rows.getDouble("x"));
                    data.set(path + ".y", rows.getDouble("y"));
                    data.set(path + ".z", rows.getDouble("z"));
                    data.set(path + ".yaw", rows.getDouble("yaw"));
                    data.set(path + ".pitch", rows.getDouble("pitch"));
                    imported++;
                }
            }
            data.set("migration.huskhomes-imported", true);
            data.set("migration.huskhomes-count", imported);
            saveData();
            getLogger().info("Imported " + imported + " homes from the HuskHomes database.");
        } catch (Exception exception) {
            getLogger().log(Level.WARNING,
                    "Could not refresh the bundled HuskHomes migration; using the migrated snapshot instead.", exception);
        }
    }

    private void migrateNamedHomes() {
        var players = data.getConfigurationSection("players");
        if (players == null) return;
        int migrated = 0;
        for (String playerId : players.getKeys(false)) {
            String playerRoot = "players." + playerId;
            if (data.isConfigurationSection(playerRoot + ".home")) {
                data.set(playerRoot + ".homes", null);
                continue;
            }
            var namedHomes = data.getConfigurationSection(playerRoot + ".homes");
            if (namedHomes == null || namedHomes.getKeys(false).isEmpty()) continue;
            String selected = namedHomes.contains("home") ? "home"
                    : namedHomes.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null);
            if (selected == null) continue;
            String source = playerRoot + ".homes." + selected;
            String target = playerRoot + ".home";
            for (String key : new String[]{"world", "world-uuid", "x", "y", "z", "yaw", "pitch"}) {
                data.set(target + "." + key, data.get(source + "." + key));
            }
            data.set(playerRoot + ".homes", null);
            migrated++;
        }
        if (migrated > 0) {
            saveData();
            getLogger().info("Collapsed named homes to one home for " + migrated + " players.");
        }
    }

    private Component success(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }

    private Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    private String capitalize(String value) {
        if (value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static final class PendingTeleport {
        private final Location target;
        private final String destinationName;
        private final String successMessage;
        private int secondsRemaining;
        private ScheduledTask task;

        private PendingTeleport(Location target, String destinationName, String successMessage,
                                int secondsRemaining) {
            this.target = target;
            this.destinationName = destinationName;
            this.successMessage = successMessage;
            this.secondsRemaining = secondsRemaining;
        }
    }

}
