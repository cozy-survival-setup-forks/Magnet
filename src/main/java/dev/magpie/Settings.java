package dev.magpie;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** config.yml, read once so the item spawn handler never touches the config. */
public final class Settings {

    public final Material block;
    public final Set<String> disabledWorlds = new HashSet<>();
    public final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    public final boolean collectCustom;
    public final int maxTypes;
    public final long maxItems;
    public final int autosellSeconds;
    public final boolean sellCustom;
    public final String priceSource;

    private Settings(FileConfiguration config, MagpiePlugin plugin) {
        Material material = Material.matchMaterial(config.getString("block", "LODESTONE").toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) {
            plugin.getLogger().warning("'block' in config.yml is not a block, using LODESTONE.");
            material = Material.LODESTONE;
        }
        block = material;

        disabledWorlds.addAll(config.getStringList("disabled-worlds"));
        for (String name : config.getStringList("blacklist")) {
            Material item = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (item == null) plugin.getLogger().warning("Unknown material in blacklist: " + name);
            else blacklist.add(item);
        }

        collectCustom = config.getBoolean("collect-custom-items", true);
        maxTypes = Math.max(1, config.getInt("max-types", 200));
        maxItems = Math.max(0, config.getLong("max-items", 0));
        autosellSeconds = Math.max(5, config.getInt("autosell-interval", 30));
        sellCustom = config.getBoolean("sell-custom-items", false);
        priceSource = config.getString("price-source", "AUTO").toUpperCase(Locale.ROOT);
    }

    public static Settings from(FileConfiguration config, MagpiePlugin plugin) {
        return new Settings(config, plugin);
    }
}
