package dev.magnet;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
    private BukkitTask autosellTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        if (!messages.load() || !configReadable()) {
            getLogger().severe("Magnet cannot start with a broken file, so it is switching itself off. Fix the file named above, "
                    + "or delete it to get the default one, then restart.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
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
        Bukkit.getScheduler().runTask(this, () -> collectors.all().forEach(this::verify));

        PluginCommand command = getCommand("magnet");
        MagnetCommand executor = new MagnetCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // Save what changed every minute, refresh open windows, sell for collectors on autosell
        Bukkit.getScheduler().runTaskTimer(this, collectors::save, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, sales::clearCache, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshMenus, 40L, 40L);
        scheduleAutosell();
        Banner.print(this, "Thanks for keeping the drops tidy.");
    }

    private void scheduleAutosell() {
        if (autosellTask != null) autosellTask.cancel();
        autosellTask = Bukkit.getScheduler().runTaskTimer(this, this::autosell, 100L, 20L * settings.autosellSeconds);
    }

    /** Bukkit's own loader only logs a broken config.yml and carries on with an empty one, so it is checked first. */
    private boolean configReadable() {
        try {
            new YamlConfiguration().load(new File(getDataFolder(), "config.yml"));
            return true;
        } catch (java.io.IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml could not be read: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onDisable() {
        if (flights != null) flights.clear();
        if (holograms != null) holograms.stop();
        if (collectors != null) collectors.close();
    }

    /** @return false if a file is broken, in which case nothing changed */
    public boolean reload() {
        if (!configReadable() || !messages.load()) return false;
        reloadConfig();
        settings = Settings.from(getConfig(), this);
        sales.load();
        holograms.start();
        scheduleAutosell();
        return true;
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

    /** True when the block of a collector in a loaded chunk is gone: removed with WorldEdit, /setblock or a plugin that breaks blocks without an event. */
    boolean blockGone(Collector collector) {
        World world = Bukkit.getWorld(collector.world);
        if (world == null || !world.isChunkLoaded(collector.x >> 4, collector.z >> 4)) return false;
        Block block = world.getBlockAt(collector.x, collector.y, collector.z);
        return block.getType().isAir() || block.isLiquid();
    }

    /**
     * A collector without its block can never be opened, so it must not keep swallowing items. If it holds items
     * the block is put back, if it is empty the collector is forgotten.
     */
    void verify(Collector collector) {
        if (!blockGone(collector) || collectors.at(Bukkit.getWorld(collector.world), collector.x, collector.z) != collector) return;
        String where = collector.x + " " + collector.y + " " + collector.z + " in " + Bukkit.getWorld(collector.world).getName();
        if (collector.total() == 0) {
            holograms.hide(collector);
            collectors.remove(collector);
            getLogger().info("The block of the empty collector at " + where + " was gone, so the collector was removed.");
        } else {
            Bukkit.getWorld(collector.world).getBlockAt(collector.x, collector.y, collector.z).setType(settings.block);
            getLogger().warning("The block of the collector at " + where + " was gone. It holds " + collector.total() + " items, so the block was put back.");
        }
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
            collectors.saveNow(collector);
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

    // The database follows the world saves, so after a crash they never disagree about where an item is
    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        if (collectors != null) collectors.save();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }
}
