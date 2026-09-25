package dev.magpie;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Selling. Money goes through Vault. Prices come from ShopGUI+ when it is installed, and from prices.yml.
 * Which one is asked first is {@code price-source} in config.yml.
 */
public final class Sales {

    /** What a sale gave. */
    public record Sale(long items, double money) {}

    private final MagpiePlugin plugin;
    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private Economy economy;
    /** Prices by kind of item, so the menu does not ask ShopGUI+ for every item every second. Cleared once a minute. */
    private final Map<ItemStack, Double> cache = new HashMap<>();
    private ShopGuiHook shop;
    private boolean shopFailed;

    Sales(MagpiePlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        prices.clear();
        cache.clear();
        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String name : yaml.getKeys(false)) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) plugin.getLogger().warning("Unknown material in prices.yml: " + name);
            else prices.put(material, yaml.getDouble(name));
        }

        economy = null;
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null) economy = provider.getProvider();
        }
        if (economy == null) plugin.getLogger().info("No Vault economy found, selling is turned off.");
    }

    public boolean enabled() {
        return economy != null;
    }

    public String format(double money) {
        return economy == null ? String.valueOf(money) : economy.format(money);
    }

    void clearCache() {
        cache.clear();
    }

    /** What one item of this kind sells for, 0 if it cannot be sold. */
    public double unitPrice(ItemStack one, OfflinePlayer owner) {
        Double cached = cache.get(one);
        if (cached != null) return cached;
        double price = lookup(one, owner);
        cache.put(one, price);
        return price;
    }

    private double lookup(ItemStack one, OfflinePlayer owner) {
        Settings settings = plugin.settings();
        if (one.hasItemMeta() && !settings.sellCustom) return 0;

        double price = 0;
        if (!settings.priceSource.equals("FILE") && !shopFailed && Bukkit.getPluginManager().isPluginEnabled("ShopGUIPlus")) {
            try {
                if (shop == null) shop = new ShopGuiHook();
                price = shop.price(one, owner);
            } catch (Exception | LinkageError ex) {
                shopFailed = true;
                plugin.getLogger().warning("Could not read prices from ShopGUI+, using prices.yml: " + ex);
            }
        }
        if (price <= 0 && !settings.priceSource.equals("SHOPGUIPLUS")) price = prices.getOrDefault(one.getType(), 0.0);
        return Math.max(0, price);
    }

    /** Sells everything in the collector that has a price and pays the owner. */
    public Sale sell(Collector collector, OfflinePlayer owner) {
        if (economy == null) return new Sale(0, 0);

        long items = 0;
        double money = 0;
        for (Collector.Entry entry : new ArrayList<>(collector.entries().values())) {
            double price = unitPrice(entry.key, owner);
            if (price <= 0) continue;
            long amount = collector.take(entry, entry.amount);
            items += amount;
            money += price * amount;
        }
        if (money > 0) economy.depositPlayer(owner, money);
        return new Sale(items, money);
    }

    /** What selling the collector would give right now, for the menu. */
    public double worth(Collector collector, OfflinePlayer owner) {
        double money = 0;
        for (Collector.Entry entry : collector.entries().values()) money += unitPrice(entry.key, owner) * entry.amount;
        return money;
    }

    List<String> knownPrices() {
        List<String> names = new ArrayList<>();
        for (Material material : prices.keySet()) names.add(material.name());
        return names;
    }
}
