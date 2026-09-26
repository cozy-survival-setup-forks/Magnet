package dev.magnet;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Magnet: one collector per chunk that picks up every item dropped in that chunk, from mobs, farms, blocks or
 * anything else, keeps them as counts and lets the owner take or sell them.
 */
public final class MagnetPlugin extends JavaPlugin implements Listener {

    private Settings settings;
    private Messages messages;
    private Collectors collectors;
    private Items items;
    private Sales sales;
    private Flights flights;
    private Holograms holograms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();
        settings = Settings.from(getConfig(), this);
        items = new Items(this);
        sales = new Sales(this);
        flights = new Flights(this);
        holograms = new Holograms(this);
        sales.load();

        collectors = new Collectors(new Storage(new File(getDataFolder(), "collectors.db"), getLogger()));
        try {
            collectors.load();
        } catch (Exception ex) {
            getLogger().severe("Could not load collectors, turning off so nothing is overwritten: " + ex);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(new CollectorListener(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(holograms, this);
        holograms.start();

        PluginCommand command = getCommand("magnet");
        MagnetCommand executor = new MagnetCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // Save what changed every minute, refresh open windows, sell for collectors on autosell
        Bukkit.getScheduler().runTaskTimer(this, collectors::save, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, sales::clearCache, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshMenus, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::autosell, 100L, 20L * settings.autosellSeconds);
    }

    @Override
    public void onDisable() {
        if (flights != null) flights.clear();
        if (holograms != null) holograms.stop();
        if (collectors != null) collectors.close();
    }

    public void reload() {
        reloadConfig();
        messages.load();
        settings = Settings.from(getConfig(), this);
        sales.load();
        holograms.start();
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Collectors collectors() {
        return collectors;
    }

    public Items items() {
        return items;
    }

    public Holograms holograms() {
        return holograms;
    }

    public Flights flights() {
        return flights;
    }

    public Sales sales() {
        return sales;
    }

    public boolean canUse(Player player, Collector collector) {
        return collector.owner.equals(player.getUniqueId()) || player.hasPermission("magnet.admin");
    }

    public void open(Player player, Collector collector) {
        if (!canUse(player, collector)) {
            messages.send(player, "not-yours");
        } else {
            new Menu(this, collector, player).open();
        }
    }

    /** Sells by hand from the window, and tells the player what they got. */
    public void sell(Collector collector, Player player) {
        Sales.Sale sale = sales.sell(collector, Bukkit.getOfflinePlayer(collector.owner));
        if (sale.items() == 0) messages.send(player, "nothing-to-sell");
        else messages.send(player, "sold", "items", String.valueOf(sale.items()), "money", sales.format(sale.money()));
    }

    private void refreshMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu) menu.render();
        }
    }

    private void autosell() {
        if (!sales.enabled()) return;
        for (Collector collector : collectors.all()) {
            if (!collector.autosell || collector.total() == 0) continue;

            OfflinePlayer owner = Bukkit.getOfflinePlayer(collector.owner);
            Sales.Sale sale = sales.sell(collector, owner);
            Player online = owner.getPlayer();
            if (online != null && sale.items() > 0) {
                messages.send(online, "autosold", "items", String.valueOf(sale.items()), "money", sales.format(sale.money()));
            }
        }
    }

    // ---- the window ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            menu.click(event.getSlot(), event.isShiftClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }
}
