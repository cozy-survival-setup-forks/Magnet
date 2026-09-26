package dev.magnet;

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
    public final boolean collectThrown;
    public final int maxTypes;
    public final long maxItems;
    public final int autosellSeconds;
    public final boolean sellCustom;
    public final String priceSource;
    public final boolean animation;
    public final int animationGround;
    public final int animationMax;
    public final boolean animationSound;
    public final boolean hologram;
    public final double hologramHeight;
    public final float hologramScale;
    public final boolean hologramShadow;
    public final boolean hologramSeeThrough;
    public final org.bukkit.Color hologramBackground;
    public final int hologramViewDistance;
    public final int hologramSeconds;
    public final java.util.List<String> hologramLines;

    private Settings(FileConfiguration config, MagnetPlugin plugin) {
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
        collectThrown = config.getBoolean("collect-thrown-items", false);
        maxTypes = Math.max(1, config.getInt("max-types", 200));
        maxItems = Math.max(0, config.getLong("max-items", 0));
        autosellSeconds = Math.max(5, config.getInt("autosell-interval", 30));
        sellCustom = config.getBoolean("sell-custom-items", false);
        priceSource = config.getString("price-source", "AUTO").toUpperCase(Locale.ROOT);

        animation = config.getBoolean("animation.enabled", true);
        animationGround = 20 * Math.max(0, Math.min(30, config.getInt("animation.ground-time", 3)));
        animationMax = Math.max(1, config.getInt("animation.max-flying", 80));
        animationSound = config.getBoolean("animation.sound", true);

        hologram = config.getBoolean("hologram.enabled", true);
        hologramHeight = Math.max(0, Math.min(10, config.getDouble("hologram.height", 0.6)));
        hologramScale = (float) Math.max(0.2, Math.min(5, config.getDouble("hologram.scale", 1.0)));
        hologramShadow = config.getBoolean("hologram.shadow", true);
        hologramSeeThrough = config.getBoolean("hologram.see-through", false);
        hologramBackground = Holograms.parseBackground(config.getString("hologram.background", "transparent"));
        hologramViewDistance = Math.max(4, config.getInt("hologram.view-distance", 24));
        hologramSeconds = Math.max(1, config.getInt("hologram.update-seconds", 2));
        java.util.List<String> lines = config.getStringList("hologram.lines");
        hologramLines = lines.isEmpty() ? java.util.List.of("<#F5C542><bold>Chunk Collector", "<gray>Items: <white><items>") : lines;
    }

    public static Settings from(FileConfiguration config, MagnetPlugin plugin) {
        return new Settings(config, plugin);
    }
}
