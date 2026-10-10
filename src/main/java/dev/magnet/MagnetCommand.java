package dev.magnet;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /magnet give <player> [amount], /magnet list [player|all], /magnet reload. */
final class MagnetCommand implements TabExecutor {

    private static final int LIST_LIMIT = 20;

    private final MagnetPlugin plugin;

    MagnetCommand(MagnetPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        Messages messages = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            list(sender, args);
            return true;
        }

        if (!sender.hasPermission("magnet.admin") || (!sub.equals("give") && !sub.equals("reload") && !sub.equals("doctor") && !sub.equals("backup"))) {
            if (sender.hasPermission("magnet.admin")) messages.send(sender, "usage");
            else messages.send(sender, "no-permission");
            return true;
        }

        if (sub.equals("reload")) {
            messages.send(sender, plugin.reload() ? "reloaded" : "reload-failed");
            return true;
        }

        if (sub.equals("doctor")) {
            if (args.length >= 3 && args[1].equalsIgnoreCase("resolve")) {
                sender.sendPlainMessage(plugin.resolvePayout(args[2]) ? "Marked as handled." : "There is no unchecked sale with that id.");
            } else {
                plugin.doctor().forEach(sender::sendPlainMessage);
            }
            return true;
        }

        if (sub.equals("backup")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("now")) sender.sendPlainMessage("Use /magnet backup now");
            else sender.sendPlainMessage(plugin.backupNow() ? "Backup made and checked." : "The backup FAILED, see the console.");
            return true;
        }

        if (args.length < 2) {
            messages.send(sender, "usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "player-not-found", "player", args[1]);
            return true;
        }

        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                messages.send(sender, "usage");
                return true;
            }
        }

        var left = target.getInventory().addItem(plugin.items().create(amount));
        left.values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        messages.send(sender, "gave", "player", target.getName(), "amount", String.valueOf(amount));
        return true;
    }

    /** /magnet list shows your own collectors, admins can name a player or say "all". */
    private void list(CommandSender sender, String[] args) {
        Messages messages = plugin.messages();
        boolean admin = sender.hasPermission("magnet.admin");
        if (!admin && !sender.hasPermission("magnet.use")) {
            messages.send(sender, "no-permission");
            return;
        }

        UUID who = null;
        if (args.length > 1) {
            if (!admin) {
                messages.send(sender, "no-permission");
                return;
            }
            if (!args[1].equalsIgnoreCase("all")) {
                OfflinePlayer other = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (other == null) {
                    messages.send(sender, "player-not-found", "player", args[1]);
                    return;
                }
                who = other.getUniqueId();
            }
        } else if (sender instanceof Player player) {
            who = player.getUniqueId();
        } else {
            messages.send(sender, "usage");
            return;
        }

        List<Collector> found = new ArrayList<>();
        for (Collector collector : plugin.collectors().all()) {
            if (who == null || collector.owner.equals(who)) found.add(collector);
        }
        found.sort(Comparator.comparingInt(collector -> collector.id));
        if (found.isEmpty()) {
            messages.send(sender, "list-none");
            return;
        }

        messages.send(sender, "list-header", "count", String.valueOf(found.size()));
        for (Collector collector : found.subList(0, Math.min(LIST_LIMIT, found.size()))) {
            World world = Bukkit.getWorld(collector.world);
            OfflinePlayer owner = Bukkit.getOfflinePlayer(collector.owner);
            messages.send(sender, "list-line", "id", String.valueOf(collector.id),
                    "world", world == null ? "?" : world.getName(),
                    "x", String.valueOf(collector.x), "y", String.valueOf(collector.y), "z", String.valueOf(collector.z),
                    "items", String.format(Locale.US, "%,d", collector.total()),
                    "owner", owner.getName() == null ? "?" : owner.getName());
        }
        if (found.size() > LIST_LIMIT) messages.send(sender, "list-more", "more", String.valueOf(found.size() - LIST_LIMIT));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission("magnet.admin");
        if (args.length == 1) {
            options.add("list");
            if (admin) {
                options.add("give");
                options.add("reload");
                options.add("doctor");
                options.add("backup");
            }
        } else if (args.length == 2 && admin && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("list"))) {
            for (Player player : Bukkit.getOnlinePlayers()) options.add(player.getName());
            if (args[0].equalsIgnoreCase("list")) options.add("all");
        } else if (args.length == 2 && admin && args[0].equalsIgnoreCase("doctor")) {
            options.add("resolve");
        } else if (args.length == 2 && admin && args[0].equalsIgnoreCase("backup")) {
            options.add("now");
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
