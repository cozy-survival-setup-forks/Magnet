package dev.magnet;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The pull animation. The real item is already counted and gone, so this is only a visual: an item display falls
 * to the ground, lies there for a while, floats up, circles the collector and shrinks into it. The client does the
 * moving (teleport and transform interpolation), the server sends a teleport about every three ticks, and the
 * task only runs while something is animating.
 */
public final class Flights implements Runnable {

    private static final int RISE_TICKS = 10;
    private static final int ORBIT_STEPS = 10;
    private static final int STEP_TICKS = 3;
    private static final int SWALLOW_TICKS = 4;
    private static final int SPIN_TICKS = 20;
    private static final double ORBIT_RADIUS = 1.4;
    private static final double END_RADIUS = 0.35;

    private enum Stage { FALL, REST, RISE, APPROACH, ORBIT, SWALLOW, DONE }

    private static final class Flight {
        final ItemDisplay display;
        final Collector collector;
        final double gx, gy, gz; // where it lies on the ground
        final double cx, cy, cz; // the middle of the collector
        final int fallTicks;
        final int groundTicks;
        Stage stage = Stage.FALL;
        int age, nextAt = 1, restEnd, step;
        float spin;
        double angle;

        Flight(ItemDisplay display, Collector collector, double gx, double gy, double gz, int fallTicks, int groundTicks) {
            this.display = display;
            this.collector = collector;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.cx = collector.x + 0.5;
            this.cy = collector.y + 0.5;
            this.cz = collector.z + 0.5;
            this.fallTicks = fallTicks;
            this.groundTicks = groundTicks;
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
        RayTraceResult hit = world.rayTraceBlocks(start, new Vector(0, -1, 0), 48, FluidCollisionMode.NEVER, true);
        if (hit == null) return;

        double groundY = hit.getHitPosition().getY() + 0.25;
        double height = start.getY() - groundY;
        int fall = 0;
        if (height < 0.3) {
            start.setY(groundY);
        } else {
            // Items fall at 0.04 blocks per tick squared, the display flies a straight line down in that time
            fall = Math.max(3, Math.min(59, (int) Math.ceil(Math.sqrt(2 * height / 0.04) * 1.05)));
        }

        ItemDisplay display = world.spawn(start, ItemDisplay.class, d -> {
            d.setItemStack(stack.asOne());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setTransformation(transform(0f, 1f));
            d.setBrightness(new Display.Brightness(15, 15)); // stays visible in dark farms
            d.setViewRange(0.5f);
            d.setPersistent(false);
            d.setInvulnerable(true);
        });
        flying.add(new Flight(display, collector, start.getX(), groundY, start.getZ(), fall, settings.animationGround));

        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    @Override
    public void run() {
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

        if (flying.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    /** Does what the flight's current stage says and schedules the next one. Returns true when it is over. */
    private boolean step(Flight f) {
        ItemDisplay display = f.display;
        switch (f.stage) {
            case FALL -> {
                if (f.fallTicks > 0) {
                    display.setTeleportDuration(f.fallTicks);
                    display.teleport(new Location(display.getWorld(), f.gx, f.gy, f.gz));
                }
                f.nextAt = f.age + f.fallTicks;
                f.restEnd = f.nextAt + f.groundTicks;
                f.stage = Stage.REST;
            }
            case REST -> {
                if (f.age >= f.restEnd) {
                    f.stage = Stage.RISE;
                    f.nextAt = f.age;
                } else {
                    // A slow turn while it lies there, half a turn every second
                    turn(f, 3.0f, SPIN_TICKS);
                    f.nextAt = Math.min(f.age + SPIN_TICKS, f.restEnd);
                }
            }
            case RISE -> {
                display.setGlowing(true);
                display.setGlowColorOverride(Color.fromRGB(0xF5C542));
                display.setTeleportDuration(RISE_TICKS);
                display.teleport(new Location(display.getWorld(), f.gx, f.gy + 1.1, f.gz));
                turn(f, 3.0f, RISE_TICKS);
                f.nextAt = f.age + RISE_TICKS;
                f.stage = Stage.APPROACH;
            }
            case APPROACH -> {
                if (plugin.collectors().at(display.getWorld(), f.collector.x, f.collector.z) != f.collector) return true;

                // Swing in to the circle around the collector at the spot nearest to where it floats
                f.angle = Math.atan2(f.gz - f.cz, f.gx - f.cx);
                double distance = Math.max(0, Math.hypot(f.gx - f.cx, f.gz - f.cz) - ORBIT_RADIUS);
                int ticks = Math.max(8, Math.min(40, (int) (distance * 1.5)));
                display.setTeleportDuration(ticks);
                display.teleport(new Location(display.getWorld(), f.cx + ORBIT_RADIUS * Math.cos(f.angle), f.cy + 0.9, f.cz + ORBIT_RADIUS * Math.sin(f.angle)));
                f.nextAt = f.age + ticks;
                f.stage = Stage.ORBIT;
                f.step = 0;
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
                display.setTransformation(transform(f.spin + 3.0f, 0.1f));
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

    private static void turn(Flight f, float radians, int ticks) {
        f.spin += radians;
        f.display.setTransformation(transform(f.spin, 1f));
        f.display.setInterpolationDelay(0);
        f.display.setInterpolationDuration(ticks);
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

    void clear() {
        for (Flight flight : flying) flight.display.remove();
        flying.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
