package dev.magnet;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The text above a collector: a text display, so it needs no other plugin. It only exists while its chunk is loaded
 * (it is never saved), and its text is only rewritten when the collector's contents changed.
 */
public final class Holograms implements Listener {

    private static final class Holo {
        TextDisplay display;
        long total = -1;
        int types = -1;

        Holo(TextDisplay display) {
            this.display = display;
        }
    }

    private final MagnetPlugin plugin;
    private final Map<Collector, Holo> shown = new HashMap<>();
    private BukkitTask task;

    Holograms(MagnetPlugin plugin) {
        this.plugin = plugin;
    }

    /** Puts the text above every collector in a loaded chunk, and starts keeping it up to date. */
    void start() {
        stop();
        Settings s = plugin.settings();
        if (!s.hologram) return;
        for (Collector collector : plugin.collectors().all()) show(collector);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, s.hologramSeconds * 20L, s.hologramSeconds * 20L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Holo holo : shown.values()) if (holo.display != null) holo.display.remove();
        shown.clear();
    }

    void show(Collector collector) {
        if (!plugin.settings().hologram || shown.containsKey(collector)) return;
        World world = Bukkit.getWorld(collector.world);
        if (world == null || !world.isChunkLoaded(collector.x >> 4, collector.z >> 4)) return;

        Holo holo = new Holo(spawn(world, collector));
        shown.put(collector, holo);
        write(collector, holo);
    }

    void hide(Collector collector) {
        Holo holo = shown.remove(collector);
        if (holo != null) holo.display.remove();
    }

    private TextDisplay spawn(World world, Collector collector) {
        Settings s = plugin.settings();
        Location at = new Location(world, collector.x + 0.5, collector.y + 1 + s.hologramHeight, collector.z + 0.5);
        return world.spawn(at, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(s.hologramShadow);
            d.setSeeThrough(s.hologramSeeThrough);
            d.setDefaultBackground(false);
            d.setBackgroundColor(s.hologramBackground);
            d.setLineWidth(400);
            d.setViewRange(Math.max(0.1f, s.hologramViewDistance / 64f));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s.hologramScale), new AxisAngle4f()));
            d.setPersistent(false);
            d.setInvulnerable(true);
        });
    }

    /** Rewrites the text of the holograms whose collector changed, and puts back the ones that were killed. */
    private void refresh() {
        for (Iterator<Map.Entry<Collector, Holo>> it = shown.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Collector, Holo> entry = it.next();
            Collector collector = entry.getKey();
            Holo holo = entry.getValue();
            World world = Bukkit.getWorld(collector.world);

            if (world == null || !world.isChunkLoaded(collector.x >> 4, collector.z >> 4)) {
                holo.display.remove();
                it.remove();
                continue;
            }
            if (!holo.display.isValid()) {
                holo.display = spawn(world, collector);
                holo.total = -1;
            }
            if (holo.total != collector.total() || holo.types != collector.entries().size()) write(collector, holo);
        }
    }

    private void write(Collector collector, Holo holo) {
        Settings s = plugin.settings();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(collector.owner);
        String ownerName = owner.getName() == null ? "?" : owner.getName();
        boolean wantsWorth = s.hologramLines.stream().anyMatch(line -> line.contains("<worth>"));
        String worth = wantsWorth && plugin.sales().enabled() ? plugin.sales().format(plugin.sales().worth(collector, owner)) : "-";

        List<Component> lines = new ArrayList<>();
        for (String line : s.hologramLines) {
            lines.add(plugin.messages().parse(line,
                    "items", String.format(Locale.US, "%,d", collector.total()),
                    "types", String.valueOf(collector.entries().size()),
                    "owner", ownerName,
                    "worth", worth));
        }
        holo.display.text(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines));
        holo.total = collector.total();
        holo.types = collector.entries().size();
    }

    // The entity is spawned a tick later, entities cannot be added while the chunk is still loading
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.settings().hologram || plugin.collectors().isEmpty()) return;
        Collector collector = plugin.collectors().at(event.getWorld(), event.getChunk().getX() << 4, event.getChunk().getZ() << 4);
        if (collector != null) Bukkit.getScheduler().runTask(plugin, () -> show(collector));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (shown.isEmpty()) return;
        Collector collector = plugin.collectors().at(event.getWorld(), event.getChunk().getX() << 4, event.getChunk().getZ() << 4);
        if (collector != null) hide(collector);
    }

    static Color parseBackground(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("transparent") || value.equalsIgnoreCase("none")) return Color.fromARGB(0, 0, 0, 0);
        String hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            if (hex.length() == 8) {
                long argb = Long.parseLong(hex, 16);
                return Color.fromARGB((int) (argb >> 24) & 0xFF, (int) (argb >> 16) & 0xFF, (int) (argb >> 8) & 0xFF, (int) argb & 0xFF);
            }
            if (hex.length() == 6) return Color.fromARGB(0x40, Integer.parseInt(hex, 16) >> 16 & 0xFF, Integer.parseInt(hex, 16) >> 8 & 0xFF, Integer.parseInt(hex, 16) & 0xFF);
        } catch (NumberFormatException ignored) {
            // falls through to transparent
        }
        return Color.fromARGB(0, 0, 0, 0);
    }
}
