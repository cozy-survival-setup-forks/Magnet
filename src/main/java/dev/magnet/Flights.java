package dev.magnet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The pull animation. The item is already counted when it drops, so everything here is a visual.
 *
 * 1. The real item plays its own drop (a mob's pop, a block breaking, a fall) as a "ghost": nobody can pick it up,
 *    it cannot merge and it is never saved, so it can neither be stolen nor survive a restart.
 * 2. Once it has lain on the ground for a while, it is swapped for an item display that floats up, circles the
 *    collector and shrinks into it. The client does the moving (teleport and transform interpolation), the server
 *    sends a teleport about every three ticks, and the task only runs while something is animating.
 */
public final class Flights implements Runnable {

    private static final int SETTLE_TICKS = 4;  // the client must know about the display before it starts to move
    private static final int MIN_MOVE_TICKS = 24; // the slow climb to the circle around the collector
    private static final int MAX_MOVE_TICKS = 57;
    private static final int ORBIT_STEPS = 10;
    private static final int STEP_TICKS = 3;
    private static final int SWALLOW_TICKS = 4;
    private static final int LANDING_PATIENCE = 140; // ticks it may take to land before it lifts off anyway
    private static final double ORBIT_RADIUS = 1.4;
    private static final double END_RADIUS = 0.35;

    private static final class Ghost {
        final Item item;
        final Collector collector;
        final int born;
        int landed = -1;

        Ghost(Item item, Collector collector, int born) {
            this.item = item;
            this.collector = collector;
            this.born = born;
        }
    }

    private enum Stage { SETTLE, MOVE, ORBIT, SWALLOW, DONE }

    private static final class Flight {
        final ItemDisplay display;
        final Collector collector;
        final double gx, gy, gz; // where it lay
        final double cx, cy, cz; // the middle of the collector
        Stage stage = Stage.SETTLE;
        int age, nextAt = SETTLE_TICKS, step, moveSteps;
        double angle;

        Flight(ItemDisplay display, Collector collector, double gx, double gy, double gz) {
            this.display = display;
            this.collector = collector;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.cx = collector.x + 0.5;
            this.cy = collector.y + 0.5;
            this.cz = collector.z + 0.5;
        }
    }

    private final MagnetPlugin plugin;
    private final List<Ghost> ghosts = new ArrayList<>();
    private final Set<UUID> ghostIds = new HashSet<>();
    private final List<Flight> flying = new ArrayList<>();
    private BukkitTask task;
    private int soundTick = -1;

    Flights(MagnetPlugin plugin) {
        this.plugin = plugin;
    }

    /** True for the harmless copies that lie on the ground until they lift off. */
    boolean isGhost(Item item) {
        return !ghostIds.isEmpty() && ghostIds.contains(item.getUniqueId());
    }

    /**
     * Keeps the spawning item as a ghost that plays out its own drop. Returns false when it should not be
     * animated (too many already, nobody near), and the caller removes the item instead.
     */
    boolean ghost(Item item, Collector collector) {
        if (ghosts.size() + flying.size() >= plugin.settings().animationMax) return false;
        if (item.getWorld().getPlayersSeeingChunk(collector.x >> 4, collector.z >> 4).isEmpty()) return false;

        item.setPickupDelay(Short.MAX_VALUE); // players and mobs cannot take it, and items with this delay do not merge
        item.setPersistent(false);
        item.setInvulnerable(true);
        ghosts.add(new Ghost(item, collector, Bukkit.getCurrentTick()));
        ghostIds.add(item.getUniqueId());

        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
        return true;
    }

    @Override
    public void run() {
        int now = Bukkit.getCurrentTick();
        int lie = plugin.settings().animationGround;

        for (Iterator<Ghost> it = ghosts.iterator(); it.hasNext(); ) {
            Ghost g = it.next();
            Item item = g.item;
            if (!item.isValid()) {
                ghostIds.remove(item.getUniqueId());
                it.remove();
                continue;
            }

            if (g.landed < 0 && (item.isOnGround() || now - g.born > LANDING_PATIENCE)) g.landed = now;
            if (g.landed < 0 || now - g.landed < lie) continue;

            ghostIds.remove(item.getUniqueId());
            it.remove();
            liftOff(g);
        }

        for (Iterator<Flight> it = flying.iterator(); it.hasNext(); ) {
            Flight f = it.next();
            if (!f.display.isValid()) {
                it.remove();
                continue;
            }
            if (++f.age < f.nextAt) continue;

            if (step(f)) {
                f.display.remove();
                it.remove();
            }
        }

        if (ghosts.isEmpty() && flying.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    /** Swaps the item lying on the ground for a display of it. */
    private void liftOff(Ghost g) {
        Item item = g.item;
        Collector collector = g.collector;
        Location at = item.getLocation().add(0, 0.25, 0);
        ItemDisplay display = null;

        // It may have drifted off in water, or the collector may be gone. Then it just disappears.
        boolean near = at.getWorld().getUID().equals(collector.world) && Math.hypot(at.getX() - collector.x, at.getZ() - collector.z) < 48;
        if (near && plugin.collectors().at(at.getWorld(), collector.x, collector.z) == collector) {
            display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(item.getItemStack().asOne());
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                d.setTransformation(transform(0f, 1f));
                d.setBrightness(new Display.Brightness(15, 15)); // stays visible in dark farms
                d.setViewRange(0.5f);
                d.setPersistent(false);
                d.setInvulnerable(true);
            });
        }
        item.remove();
        if (display != null) flying.add(new Flight(display, collector, at.getX(), at.getY(), at.getZ()));
    }

    /** Does what the flight's current stage says and schedules the next one. Returns true when it is over. */
    private boolean step(Flight f) {
        ItemDisplay display = f.display;
        switch (f.stage) {
            case SETTLE -> {
                if (plugin.collectors().at(display.getWorld(), f.collector.x, f.collector.z) != f.collector) return true;

                // A slow climb toward the circle around the collector, in the direction it lies from the collector
                f.angle = Math.atan2(f.gz - f.cz, f.gx - f.cx);
                double distance = Math.max(0, Math.hypot(f.gx - f.cx, f.gz - f.cz) - ORBIT_RADIUS);
                int ticks = Math.max(MIN_MOVE_TICKS, Math.min(MAX_MOVE_TICKS, (int) (distance * 2.5)));
                f.moveSteps = ticks / STEP_TICKS;
                f.step = 0;
                display.setTeleportDuration(STEP_TICKS);
                display.setTransformation(transform(3.0f, 1f)); // half a turn on the way
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(f.moveSteps * STEP_TICKS);
                f.nextAt = f.age;
                f.stage = Stage.MOVE;
            }
            case MOVE -> {
                // Horizontal speed is even, the height eases in and out, so it lifts off gently instead of jumping up
                f.step++;
                double p = f.step / (double) f.moveSteps;
                double ease = p * p * (3 - 2 * p);
                double endX = f.cx + ORBIT_RADIUS * Math.cos(f.angle);
                double endZ = f.cz + ORBIT_RADIUS * Math.sin(f.angle);
                display.teleport(new Location(display.getWorld(), f.gx + (endX - f.gx) * p, f.gy + (f.cy + 0.9 - f.gy) * ease, f.gz + (endZ - f.gz) * p));
                f.nextAt = f.age + STEP_TICKS;
                if (f.step >= f.moveSteps) {
                    f.step = 0;
                    f.stage = Stage.ORBIT;
                }
            }
            case ORBIT -> {
                // One lap, spiralling in and sinking a little
                if (f.step == 0) display.setTeleportDuration(STEP_TICKS);
                f.step++;
                double t = f.step / (double) ORBIT_STEPS;
                double angle = f.angle + 2 * Math.PI * t;
                double radius = ORBIT_RADIUS - (ORBIT_RADIUS - END_RADIUS) * t;
                display.teleport(new Location(display.getWorld(), f.cx + radius * Math.cos(angle), f.cy + 0.9 - 0.5 * t, f.cz + radius * Math.sin(angle)));
                f.nextAt = f.age + STEP_TICKS;
                if (f.step >= ORBIT_STEPS) f.stage = Stage.SWALLOW;
            }
            case SWALLOW -> {
                display.setTeleportDuration(SWALLOW_TICKS);
                display.teleport(new Location(display.getWorld(), f.cx, f.cy, f.cz));
                display.setTransformation(transform(6.0f, 0.1f));
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(SWALLOW_TICKS);
                f.nextAt = f.age + SWALLOW_TICKS + 1;
                f.stage = Stage.DONE;
            }
            case DONE -> {
                arrive(f);
                return true;
            }
        }
        return false;
    }

    private static Transformation transform(float rotation, float scale) {
        return new Transformation(new Vector3f(), new AxisAngle4f(rotation, 0, 1, 0), new Vector3f(scale), new AxisAngle4f());
    }

    private void arrive(Flight f) {
        World world = f.display.getWorld();
        world.spawnParticle(Particle.END_ROD, f.cx, f.cy + 0.6, f.cz, 1, 0.15, 0.05, 0.15, 0.01);
        int tick = Bukkit.getCurrentTick();
        if (plugin.settings().animationSound && tick != soundTick) {
            soundTick = tick;
            world.playSound(new Location(world, f.cx, f.cy, f.cz), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.5f);
        }
    }

    /** Removes everything on screen. The items are already in their collectors, so nothing is lost. */
    void clear() {
        for (Ghost g : ghosts) g.item.remove();
        for (Flight f : flying) f.display.remove();
        ghosts.clear();
        ghostIds.clear();
        flying.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
