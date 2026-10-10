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

    // Regel 1: Erze, die an natürlicher Luft/Höhle/Wasser liegen (sichtbar)
    // Alarm bei MEHR als VISIBLE_THRESHOLD Erzen innerhalb von VISIBLE_WINDOW_MS
    private static final long VISIBLE_WINDOW_MS = 60_000L;
    private static final int VISIBLE_THRESHOLD = 30;

    // Regel 2: Versteckte Erze (Luft daneben nur vom Spieler selbst gegraben)
    // Alarm, wenn mindestens minOres versteckte Erze abgebaut wurden UND
    // diese mindestens minShare (0.10 = 10 %) aller abgebauten Blöcke im Zeitfenster ausmachen
    private record Ratio(String label, long ms, int minOres, double minShare) {}

    private static final Ratio[] RATIOS = {
            new Ratio("10 Minuten", 600_000L, 6, 0.10),
            new Ratio("1 Stunde", 3_600_000L, 15, 0.05)
    };

    // So lange gilt ein vom Spieler gegrabener Block als "selbst gegraben"
    private static final long DUG_MEMORY_MS = 600_000L;

    // So lange werden Abbau-Zeitpunkte insgesamt gemerkt
    private static final long KEEP_MS = 3_600_000L;
    private static final int MAX_HITS = 50_000;

    // ===== Ab hier nichts ändern, außer du weißt, was du tust =====

    // Ein abgebauter Block: Zeit, Erzgruppe (null = kein überwachtes Erz), natürlich frei?
    private record Hit(long time, String group, boolean exposed) {}

    private static final class PlayerData {
        final Deque<Hit> hits = new ArrayDeque<>();
        final Map<Long, Long> dug = new HashMap<>();
        final Map<String, Long> lastAlert = new HashMap<>();
    }

    private final Map<UUID, PlayerData> data = new HashMap<>();
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

    // Eindeutiger Zahlenschlüssel für die Position eines Blocks
    private static long key(Block block) {
        return ((long) (block.getX() & 0x3FFFFFF) << 38)
                | ((long) (block.getZ() & 0x3FFFFFF) << 12)
                | (long) (block.getY() & 0xFFF);
    }

    // true = das Erz grenzt an natürliche Luft/Höhle/Wasser (also sichtbar)
    // false = das Erz war ringsum fest, oder die Luft daneben hat der Spieler selbst gegraben
    private boolean isNaturallyExposed(Block block, PlayerData pd, long now) {
        BlockFace[] faces = {
                BlockFace.UP, BlockFace.DOWN,
                BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST
        };
        for (BlockFace face : faces) {
            Block neighbor = block.getRelative(face);
            if (neighbor.isLiquid() || !neighbor.getType().isSolid()) {
                Long dugTime = pd.dug.get(key(neighbor));
                if (dugTime == null || now - dugTime > DUG_MEMORY_MS) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }

        Block block = event.getBlock();
        long now = System.currentTimeMillis();
        PlayerData pd = data.computeIfAbsent(player.getUniqueId(), k -> new PlayerData());

        String group = groupOf(block.getType());
        // Wichtig: erst prüfen, danach den Block als "gegraben" merken
        boolean exposed = group != null && isNaturallyExposed(block, pd, now);

        pd.hits.addLast(new Hit(now, group, exposed));
        pd.dug.put(key(block), now);

        while (!pd.hits.isEmpty()
                && (now - pd.hits.peekFirst().time() > KEEP_MS || pd.hits.size() > MAX_HITS)) {
            pd.hits.pollFirst();
        }
        if (pd.dug.size() > 20_000) {
            pd.dug.values().removeIf(t -> now - t > DUG_MEMORY_MS);
        }

        if (group != null) {
            checkRules(player, pd, group, now);
        }
    }

    private void checkRules(Player player, PlayerData pd, String group, long now) {
        // Regel 1: viele sichtbare Erze in kurzer Zeit
        String visibleKey = group + ":visible";
        long visibleSince = Math.max(now - VISIBLE_WINDOW_MS, pd.lastAlert.getOrDefault(visibleKey, 0L));
        int visibleCount = 0;
        long firstVisible = now;
        for (Hit hit : pd.hits) {
            if (hit.time() > visibleSince && group.equals(hit.group()) && hit.exposed()) {
                visibleCount++;
                if (hit.time() < firstVisible) {
                    firstVisible = hit.time();
                }
            }
        }
        if (visibleCount > VISIBLE_THRESHOLD) {
            pd.lastAlert.put(visibleKey, now);
            alertAdmins(player, player.getName() + ": " + visibleCount + "x " + group
                    + " an natürlichen Höhlen/Luft in " + duration(now - firstVisible) + " ");
            return;
        }

        // Regel 2: auffällig viele versteckte Erze im Verhältnis zu allen abgebauten Blöcken
        for (Ratio ratio : RATIOS) {
            String ratioKey = group + ":" + ratio.label();
            long since = Math.max(now - ratio.ms(), pd.lastAlert.getOrDefault(ratioKey, 0L));

            int total = 0;
            int hidden = 0;
            long first = now;
            for (Hit hit : pd.hits) {
                if (hit.time() > since) {
                    total++;
                    if (group.equals(hit.group()) && !hit.exposed()) {
                        hidden++;
                        if (hit.time() < first) {
                            first = hit.time();
                        }
                    }
                }
            }

            if (hidden >= ratio.minOres() && (double) hidden / total >= ratio.minShare()) {
                long percent = Math.round(100.0 * hidden / total);
                pd.lastAlert.put(ratioKey, now);
                alertAdmins(player, player.getName() + ": " + hidden + "x " + group
                        + " versteckt abgebaut (" + percent + " % aller Blöcke, "
                        + duration(now - first) + ") ");
                return;
            }
        }
    }

    private String duration(long ms) {
        long seconds = Math.max(1, ms / 1000);
        if (seconds < 120) {
            return seconds + " Sekunden";
        }
        return (seconds / 60) + " Minuten";
    }

    private void alertAdmins(Player miner, String text) {
        TextComponent message = new TextComponent("[OreAlert] " + text);
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
        getLogger().info("[OreAlert] " + text);
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
        data.remove(event.getPlayer().getUniqueId());
    }
}
