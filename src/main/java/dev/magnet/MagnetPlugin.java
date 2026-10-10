package dev.magnet;

import dev.magnet.safe.ConfigMigrator;
import dev.magnet.safe.Doctor;
import dev.magnet.safe.FileBackups;
import dev.magnet.safe.Guard;
import dev.magnet.safe.Health;
import dev.magnet.safe.Prep;
import dev.magnet.safe.SafeIo;
import dev.magnet.safe.ServerId;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Magnet: one collector per chunk that picks up every item dropped in that chunk, from mobs, farms, blocks or
 * anything else, keeps them as counts and lets the owner take or sell them.
 */
public final class MagnetPlugin extends JavaPlugin implements Listener {

    private static final int CONFIG_VERSION = 1;
    private static final int LANG_VERSION = 1;
    /** The price list the owner writes: only kept readable and backed up, nothing is added to it. */
    private static final String PRICES = "prices.yml";

    private final List<Prep.Spec> files = List.of(
            new Prep.Spec("config.yml", "config-version", CONFIG_VERSION, Prep.configMigrator(CONFIG_VERSION), rules -> {
                rules.range("max-types", 1, 100_000);
                rules.range("max-items", 0, 2_000_000_000);
                rules.range("autosell-interval", 5, 86_400);
                rules.range("animation.ground-time", 0, 30);
                rules.range("animation.max-flying", 1, 1000);
                rules.range("hologram.height", 0, 10);
                rules.range("hologram.scale", 0.2, 5);
                rules.range("hologram.view-distance", 4, 256);
                rules.range("hologram.update-seconds", 1, 3600);
                rules.oneOf("price-source", "AUTO", "SHOPGUIPLUS", "FILE");
                rules.range("backup.interval-hours", 1, 168);
                rules.range("backup.keep", 1, 90);
            }),
            new Prep.Spec("messages.yml", "lang-version", LANG_VERSION, new ConfigMigrator("lang-version", LANG_VERSION), null));

    private BukkitTask backupTask;
    private boolean started;
    private Storage storage;
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
        Health.storage("SQLite collectors.db for the collectors and what they hold; YAML for config.yml, messages.yml and prices.yml");
        Prep.startup(this, files);
        Path prices = getDataFolder().toPath().resolve(PRICES);
        // a damaged price list is put back from its .bak; without one it is left as it is, as before
        if (Files.exists(prices) && !SafeIo.parses(prices) && SafeIo.parses(SafeIo.backupOf(prices))) {
            SafeIo.loadYaml(prices, SafeIo.Policy.SETTINGS, getLogger());
        } else {
            SafeIo.refreshBackup(prices);
        }
        reloadConfig();
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

        storage = new Storage(new File(getDataFolder(), "collectors.db"), getLogger());
        collectors = new Collectors(storage);
        try {
            storage.open(backupKeep());
            collectors.load();
        } catch (Exception ex) {
            // the players' items are never replaced by an empty set: the plugin stays off until it is sorted out
            getLogger().severe("Magnet cannot use collectors.db and is switching itself off so no items are lost or overwritten: " + ex.getMessage());
            Health.failure("collectors.db could not be opened: " + ex.getMessage());
            collectors = null;
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
        boolean beacon = getConfig().getBoolean("metrics.enabled", true);
        Metrics.start(this, ServerId.resolve(getDataFolder().toPath(), storage.serverIdSlot(), beacon, getLogger()));
        scheduleBackups();
        started = true;
        boolean shopGui = Bukkit.getPluginManager().isPluginEnabled("ShopGUIPlus");
        getLogger().info("Ready: " + collectors.all().size() + " collector(s) loaded, selling "
                + (sales.enabled() ? "on (prices from " + (settings.priceSource.equals("FILE") ? "prices.yml" : shopGui ? "ShopGUI+" + (settings.priceSource.equals("AUTO") ? " and prices.yml" : "") : "prices.yml") + ")" : "off (needs Vault and an economy plugin)")
                + ", animation " + (settings.animation ? "on" : "off") + ", hologram " + (settings.hologram ? "on" : "off") + ".");
        if (settings.priceSource.equals("SHOPGUIPLUS") && !shopGui) {
            getLogger().warning("price-source is SHOPGUIPLUS but ShopGUI+ is not installed, so nothing can be sold.");
        }
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
        if (backupTask != null) backupTask.cancel();
        if (collectors != null) collectors.close();
    }

    private int backupKeep() {
        return Math.max(1, Math.min(90, getConfig().getInt("backup.keep", 7)));
    }

    /** (Re)starts the timer of the database copies from backup.interval-hours and backup.keep. */
    private void scheduleBackups() {
        int hours = Math.max(1, Math.min(168, getConfig().getInt("backup.interval-hours", 6)));
        storage.configureBackups(backupKeep());
        if (backupTask != null) backupTask.cancel();
        backupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, storage::backup, 20L * 60, hours * 3600L * 20L);
    }

    /** The text of /magnet doctor. */
    List<String> doctor() {
        List<String> extra = new ArrayList<>(Prep.versionLines(this, files));
        extra.add("Database: collectors.db, schema " + storage.schemaVersion() + " (this plugin writes " + Storage.SCHEMA + ")");
        extra.add("Collectors: " + collectors.all().size());
        String newest = storage.newestBackup();
        extra.add("Newest database copy on disk: " + (newest == null ? "none yet" : newest));
        extra.add("Pending writes: " + storage.pendingWrites() + ", failed writes since start: " + storage.failedWrites());
        try {
            List<String> unknown = storage.journal() == null ? List.of() : storage.journal().unknown();
            extra.add("Sales that may or may not have been paid (not repeated): " + unknown.size());
            unknown.forEach(line -> extra.add("  " + line + "   (after checking: /magnet doctor resolve <id>)"));
        } catch (SQLException e) {
            extra.add("Sale record could not be read: " + e.getMessage());
        }
        return Doctor.report(getName(), getPluginMeta().getVersion(), extra);
    }

    boolean resolvePayout(String id) {
        try {
            return storage.journal() != null && storage.journal().resolve(id);
        } catch (SQLException e) {
            getLogger().severe("Could not update the sale record: " + e.getMessage());
            return false;
        }
    }

    /** /magnet backup now: a checked copy of the database and of the settings and price files. */
    boolean backupNow() {
        collectors.save();
        boolean database = storage.backup();
        List<String> names = new ArrayList<>(Prep.fileNames(files));
        names.add(PRICES);
        boolean settingsFiles = FileBackups.snapshot(getDataFolder().toPath(), names, 5, getLogger());
        return database && settingsFiles;
    }

    /** @return false if a file is broken, in which case nothing changed */
    public boolean reload() {
        if (started) {
            List<Guard.Problem> problems = Prep.validate(this, files);
            if (!problems.isEmpty()) {
                Prep.logRejected(this, problems);
                return false;
            }
        }
        if (!configReadable() || !messages.load()) return false;
        reloadConfig();
        scheduleBackups();
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
