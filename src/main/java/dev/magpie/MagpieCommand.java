package dev.magpie;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /magpie give <player> [amount], /magpie reload. */
final class MagpieCommand implements TabExecutor {

    private final MagpiePlugin plugin;

    MagpieCommand(MagpiePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        Messages messages = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (!sender.hasPermission("magpie.admin") || (!sub.equals("give") && !sub.equals("reload"))) {
            if (sender.hasPermission("magpie.admin")) messages.send(sender, "usage");
            else messages.send(sender, "no-permission");
            return true;
        }

        if (sub.equals("reload")) {
            plugin.reload();
            messages.send(sender, "reloaded");
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

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        if (!sender.hasPermission("magpie.admin")) return options;

        if (args.length == 1) {
            options.add("give");
            options.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) options.add(player.getName());
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
