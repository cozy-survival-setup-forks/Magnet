package dev.magnet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Collects the items that spawn in a collector's chunk, and looks after the collector block itself.
 */
public final class CollectorListener implements Listener {

    private final MagnetPlugin plugin;

    // Items the collector must leave alone: what a fishing rod pulls in, and what a player drops when they die
    private final Set<UUID> ignored = new HashSet<>();
    private int deathTick = -1;
    private World deathWorld;
    private double deathX, deathZ;

    public CollectorListener(MagnetPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- collecting ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Collectors collectors = plugin.collectors();
        if (collectors.isEmpty()) return;

        Item item = event.getEntity();
        Collector collector = collectors.at(item.getWorld(), item.getX(), item.getZ());
        if (collector == null) return;

        // Things a player threw, and items with an infinite pickup delay (display items of other plugins), stay put
        if (item.getThrower() != null || item.getPickupDelay() >= Short.MAX_VALUE) return;
        if (!ignored.isEmpty() && ignored.remove(item.getUniqueId())) return;
        if (deathTick == Bukkit.getCurrentTick() && item.getWorld() == deathWorld
                && Math.abs(item.getX() - deathX) < 6 && Math.abs(item.getZ() - deathZ) < 6) return;

        Settings settings = plugin.settings();
        ItemStack stack = item.getItemStack();
        if (settings.blacklist.contains(stack.getType())) return;
        if (!settings.collectCustom && stack.hasItemMeta()) return;

        int taken = collector.add(stack, settings);
        if (taken == 0) return;
        if (settings.animation) plugin.flights().launch(item, collector, stack);

        if (taken >= stack.getAmount()) {
            event.setCancelled(true);
        } else {
            stack.setAmount(stack.getAmount() - taken); // the collector was full, the rest drops as usual
            item.setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Location location = event.getPlayer().getLocation();
        deathTick = Bukkit.getCurrentTick();
        deathWorld = location.getWorld();
        deathX = location.getX();
        deathZ = location.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        // The catch spawns right after this event, and it belongs to the angler
        if (event.getCaught() instanceof Item caught) ignored.add(caught.getUniqueId());
    }

    // ---- placing, opening and breaking ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.items().isCollector(event.getItemInHand())) return;

        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        Messages messages = plugin.messages();

        if (!player.hasPermission("magnet.use")) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        } else if (plugin.settings().disabledWorlds.contains(block.getWorld().getName())) {
            event.setCancelled(true);
            messages.send(player, "disabled-world");
        } else if (plugin.collectors().at(block.getWorld(), block.getX(), block.getZ()) != null) {
            event.setCancelled(true);
            messages.send(player, "chunk-taken");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent event) {
        if (!plugin.items().isCollector(event.getItemInHand())) return;

        plugin.collectors().create(event.getBlockPlaced(), event.getPlayer().getUniqueId());
        plugin.messages().send(event.getPlayer(), "placed");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        Collector collector = block == null ? null : plugin.collectors().at(block);
        if (collector == null) return;

        Player player = event.getPlayer();
        // Sneaking with something in hand is how you build against it
        if (player.isSneaking() && !player.getInventory().getItemInMainHand().getType().isAir()) return;

        event.setCancelled(true);
        plugin.open(player, collector);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Collector collector = plugin.collectors().at(event.getBlock());
        if (collector == null) return;

        Player player = event.getPlayer();
        if (!plugin.canUse(player, collector)) {
            event.setCancelled(true);
            plugin.messages().send(player, "not-yours");
        } else if (collector.total() > 0) {
            event.setCancelled(true);
            plugin.messages().send(player, "not-empty");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent event) {
        Collector collector = plugin.collectors().at(event.getBlock());
        if (collector == null) return;

        plugin.collectors().remove(collector);
        event.setDropItems(false);
        if (event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), plugin.items().create(1));
        }
        plugin.messages().send(event.getPlayer(), "broken");
    }

    // ---- keeping the block safe ----

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (moves(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (moves(event.getBlocks())) event.setCancelled(true);
    }

    private void protect(List<Block> blocks) {
        if (!plugin.collectors().isEmpty()) blocks.removeIf(block -> plugin.collectors().at(block) != null);
    }

    private boolean moves(List<Block> blocks) {
        if (plugin.collectors().isEmpty()) return false;
        for (Block block : blocks) if (plugin.collectors().at(block) != null) return true;
        return false;
    }
}
