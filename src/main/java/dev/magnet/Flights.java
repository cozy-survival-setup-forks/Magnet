package dev.magnet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The pull animation: a small item display lifts off where the item dropped, swoops over and shrinks into the
 * collector. It is only a visual. The items are already in the collector when it starts, so nothing can be lost
 * or duplicated. The client does the moving (teleport and transform interpolation), the server only teleports
 * each display twice, and the task only runs while something is flying.
 */
public final class Flights implements Runnable {

    private static final class Flight {
        final ItemDisplay display;
        final Location mid;
        final Location end;
        int age;

        Flight(ItemDisplay display, Location mid, Location end) {
            this.display = display;
            this.mid = mid;
            this.end = end;
        }
    }

    private final MagnetPlugin plugin;
    private final List<Flight> flying = new ArrayList<>();
    private BukkitTask task;
    private int soundTick = -1;

    Flights(MagnetPlugin plugin) {
        this.plugin = plugin;
    }

    void launch(Item item, Collector collector, ItemStack stack) {
        Settings settings = plugin.settings();
        if (flying.size() >= settings.animationMax) return;

        World world = item.getWorld();
        // Nobody close enough to see it, so nothing to draw
        if (world.getPlayersSeeingChunk(collector.x >> 4, collector.z >> 4).isEmpty()) return;

        Location start = item.getLocation().add(0, 0.25, 0);
        Location end = new Location(world, collector.x + 0.5, collector.y + 0.5, collector.z + 0.5);
        Location mid = new Location(world, (start.getX() + end.getX()) / 2, Math.max(start.getY(), end.getY()) + 0.9, (start.getZ() + end.getZ()) / 2);
        int leg = settings.animationTicks / 2;

        ItemDisplay display = world.spawn(start, ItemDisplay.class, d -> {
            d.setItemStack(stack.asOne());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.5f), new AxisAngle4f()));
            d.setTeleportDuration(leg);
            d.setBrightness(new Display.Brightness(15, 15)); // stays visible in dark farms
            d.setViewRange(0.5f);
            d.setPersistent(false);
            d.setInvulnerable(true);
        });
        flying.add(new Flight(display, mid, end));

        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    @Override
    public void run() {
        int leg = plugin.settings().animationTicks / 2;
        for (Iterator<Flight> it = flying.iterator(); it.hasNext(); ) {
            Flight flight = it.next();
            ItemDisplay display = flight.display;
            if (!display.isValid()) {
                it.remove();
                continue;
            }

            flight.age++;
            if (flight.age == 1) {
                // Up and over, spinning half a turn while it shrinks
                display.setTeleportDuration(leg);
                display.teleport(flight.mid);
                display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(3.0f, 0, 1, 0), new Vector3f(0.15f), new AxisAngle4f()));
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(leg * 2);
            } else if (flight.age == 1 + leg) {
                display.teleport(flight.end);
            } else if (flight.age >= 2 + leg * 2) {
                arrive(flight);
                display.remove();
                it.remove();
            }
        }

        if (flying.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    private void arrive(Flight flight) {
        World world = flight.end.getWorld();
        world.spawnParticle(Particle.END_ROD, flight.end.getX(), flight.end.getY() + 0.6, flight.end.getZ(), 1, 0.15, 0.05, 0.15, 0.01);
        int tick = Bukkit.getCurrentTick();
        if (plugin.settings().animationSound && tick != soundTick) {
            soundTick = tick;
            world.playSound(flight.end, Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.5f);
        }
    }

    void clear() {
        for (Flight flight : flying) flight.display.remove();
        flying.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
