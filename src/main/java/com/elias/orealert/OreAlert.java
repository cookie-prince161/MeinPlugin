package com.elias.orealert;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OreAlert extends JavaPlugin implements Listener, CommandExecutor {

    // ===== EINSTELLUNGEN: hier kannst du die Zahlen ändern =====

    // Ein Zeitfenster: Name, Länge in Millisekunden, Alarm bei MEHR als dieser Anzahl
    private record Window(String label, long ms, int threshold) {}

    private static final Window[] WINDOWS = {
            new Window("1 Minute", 60_000L, 10),
            new Window("10 Minuten", 600_000L, 25),
            new Window("1 Stunde", 3_600_000L, 60)
    };

    // Regel für versteckte Erze (Zeitfenster 10 Minuten):
    // Alarm, wenn mindestens HIDDEN_MIN_COUNT versteckte Erze abgebaut wurden
    // UND mindestens HIDDEN_MIN_SHARE (0.75 = 75 %) aller Erze der Gruppe versteckt waren
    private static final long HIDDEN_WINDOW_MS = 600_000L;
    private static final int HIDDEN_MIN_COUNT = 8;
    private static final double HIDDEN_MIN_SHARE = 0.75;

    // Längstes Zeitfenster, so lange werden Abbau-Zeitpunkte gemerkt
    private static final long KEEP_MS = 3_600_000L;

    // ===== Ab hier nichts ändern, außer du weißt, was du tust =====

    private record Hit(long time, boolean hidden) {}

    // Wer hat wann welche Erzgruppe abgebaut
    private final Map<UUID, Map<String, Deque<Hit>>> tracker = new HashMap<>();
    // Wann zuletzt Alarm ausgelöst wurde (Schlüssel: Spieler + Gruppe + Regel)
    private final Map<String, Long> lastAlert = new HashMap<>();
    // Gespeicherter Ort und Spielmodus der Admins für /return
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, GameMode> returnModes = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("oreinspect").setExecutor(this);
        getCommand("return").setExecutor(this);
        getLogger().info("OreAlert aktiviert.");
    }

    // Gibt den Namen der Erzgruppe zurück oder null, wenn der Block nicht überwacht wird
    private String groupOf(Material type) {
        if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) {
            return "Diamanten";
        }
        if (type == Material.ANCIENT_DEBRIS) {
            return "Antiken Schutt (Netherite)";
        }
        if (type == Material.DEEPSLATE_COAL_ORE) {
            return "Tiefenschiefer-Kohle";
        }
        return null;
    }

    // Ein Erz gilt als versteckt, wenn es auf allen sechs Seiten von festen Blöcken umgeben ist
    private boolean isHidden(Block block) {
        BlockFace[] faces = {
                BlockFace.UP, BlockFace.DOWN,
                BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST
        };
        for (BlockFace face : faces) {
            Block neighbor = block.getRelative(face);
            if (neighbor.isLiquid() || !neighbor.getType().isSolid()) {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }

        Block block = event.getBlock();
        String group = groupOf(block.getType());
        if (group == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Deque<Hit> hits = tracker
                .computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(group, k -> new ArrayDeque<>());

        hits.addLast(new Hit(now, isHidden(block)));
        while (!hits.isEmpty() && now - hits.peekFirst().time() > KEEP_MS) {
            hits.pollFirst();
        }

        checkRules(player, group, hits, now);
    }

    private void checkRules(Player player, String group, Deque<Hit> hits, long now) {
        UUID id = player.getUniqueId();

        // Regel 1: Anzahl pro Zeitfenster
        for (Window window : WINDOWS) {
            String key = id + ":" + group + ":" + window.label();
            long since = Math.max(now - window.ms(), lastAlert.getOrDefault(key, 0L));

            int count = 0;
            int hiddenCount = 0;
            long first = now;
            for (Hit hit : hits) {
                if (hit.time() > since) {
                    count++;
                    if (hit.hidden()) {
                        hiddenCount++;
                    }
                    if (hit.time() < first) {
                        first = hit.time();
                    }
                }
            }

            if (count > window.threshold()) {
                lastAlert.put(key, now);
                alertAdmins(player, group, count, hiddenCount, now - first,
                        "Zeitfenster " + window.label());
                return;
            }
        }

        // Regel 2: auffällig viele versteckte Erze
        String hiddenKey = id + ":" + group + ":hidden";
        long since = Math.max(now - HIDDEN_WINDOW_MS, lastAlert.getOrDefault(hiddenKey, 0L));

        int count = 0;
        int hiddenCount = 0;
        long first = now;
        for (Hit hit : hits) {
            if (hit.time() > since) {
                count++;
                if (hit.hidden()) {
                    hiddenCount++;
                }
                if (hit.time() < first) {
                    first = hit.time();
                }
            }
        }

        if (hiddenCount >= HIDDEN_MIN_COUNT && (double) hiddenCount / count >= HIDDEN_MIN_SHARE) {
            lastAlert.put(hiddenKey, now);
            alertAdmins(player, group, count, hiddenCount, now - first,
                    "viele versteckte Erze");
        }
    }

    private String duration(long ms) {
        long seconds = Math.max(1, ms / 1000);
        if (seconds < 120) {
            return seconds + " Sekunden";
        }
        return (seconds / 60) + " Minuten";
    }

    private void alertAdmins(Player miner, String group, int count, int hiddenCount,
                             long spanMs, String reason) {
        String text = "[OreAlert] " + miner.getName() + ": " + count + "x " + group
                + ", davon " + hiddenCount + " versteckt, in " + duration(spanMs)
                + " (" + reason + ") ";

        TextComponent message = new TextComponent(text);
        message.setColor(ChatColor.RED);

        TextComponent button = new TextComponent("[Hinteleportieren]");
        button.setColor(ChatColor.GREEN);
        button.setBold(true);
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/oreinspect " + miner.getName()));
        message.addExtra(button);

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("orealert.notify")) {
                admin.spigot().sendMessage(message);
            }
        }
        getLogger().info(text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Dieser Befehl geht nur im Spiel.");
            return true;
        }
        Player admin = (Player) sender;

        if (command.getName().equalsIgnoreCase("oreinspect")) {
            if (args.length < 1) {
                admin.sendMessage(ChatColor.RED + "Benutzung: /oreinspect <Spieler>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                admin.sendMessage(ChatColor.RED + "Spieler nicht online.");
                return true;
            }

            // Nur beim ersten Mal speichern, damit der Ursprungsort erhalten bleibt,
            // falls du dich von Spieler zu Spieler teleportierst
            if (!returnLocations.containsKey(admin.getUniqueId())) {
                returnLocations.put(admin.getUniqueId(), admin.getLocation().clone());
                returnModes.put(admin.getUniqueId(), admin.getGameMode());
            }

            admin.setGameMode(GameMode.SPECTATOR);
            admin.teleport(target.getLocation());
            admin.sendMessage(ChatColor.YELLOW + "Du beobachtest " + target.getName()
                    + ". Mit /return kommst du zurück.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("return")) {
            Location back = returnLocations.remove(admin.getUniqueId());
            GameMode oldMode = returnModes.remove(admin.getUniqueId());
            if (back == null || oldMode == null) {
                admin.sendMessage(ChatColor.RED + "Es ist kein Rückkehrpunkt gespeichert.");
                return true;
            }
            admin.teleport(back);
            admin.setGameMode(oldMode);
            admin.sendMessage(ChatColor.GREEN + "Zurück an deinem alten Ort.");
            return true;
        }

        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        tracker.remove(id);
        lastAlert.keySet().removeIf(key -> key.startsWith(id.toString()));
    }
}
