package com.elias.orealert;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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

    // Zeitfenster in Millisekunden (60 Sekunden)
    private static final long WINDOW_MS = 60_000L;
    // Alarm, wenn MEHR als diese Anzahl im Zeitfenster abgebaut wurde
    private static final int THRESHOLD = 10;

    // Wer hat wann welche Erzgruppe abgebaut
    private final Map<UUID, Map<String, Deque<Long>>> tracker = new HashMap<>();
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }

        String group = groupOf(event.getBlock().getType());
        if (group == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Deque<Long> times = tracker
                .computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(group, k -> new ArrayDeque<>());

        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.pollFirst();
        }

        if (times.size() > THRESHOLD) {
            int count = times.size();
            times.clear(); // danach muss erst wieder neu gesammelt werden, kein Spam
            alertAdmins(player, group, count);
        }
    }

    private void alertAdmins(Player miner, String group, int count) {
        String text = "[OreAlert] " + miner.getName() + " hat " + count + "x " + group
                + " in unter einer Minute abgebaut. ";

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
        tracker.remove(event.getPlayer().getUniqueId());
    }
}
