package dev.magnet;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One collector block and what it holds. Items are stored as a count per kind of item, so a million cobblestone is a
 * single number. Main thread only.
 */
public final class Collector {

    /** One kind of item. {@code bytes} is what gets saved, made once when the kind is first seen. */
    public static final class Entry {
        public final ItemStack key;
        final byte[] bytes;
        public long amount;

        Entry(ItemStack key, byte[] bytes, long amount) {
            this.key = key;
            this.bytes = bytes;
            this.amount = amount;
        }
    }

    public final int id;
    public final UUID world;
    public final int x, y, z;
    public final UUID owner;
    public boolean autosell;
    boolean dirty;
    long total;
    final Map<ItemStack, Entry> entries = new LinkedHashMap<>();

    Collector(int id, UUID world, int x, int y, int z, UUID owner, boolean autosell) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.owner = owner;
        this.autosell = autosell;
    }

    public long total() {
        return total;
    }

    public Map<ItemStack, Entry> entries() {
        return entries;
    }

    /**
     * Stores as much of the stack as there is room for.
     *
     * @return how many items were taken
     */
    int add(ItemStack stack, Settings settings) {
        int amount = stack.getAmount();
        if (settings.maxItems > 0) amount = (int) Math.min(amount, settings.maxItems - total);
        if (amount <= 0) return 0;

        ItemStack key = stack.asOne();
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= settings.maxTypes) return 0;
            entry = new Entry(key, key.serializeAsBytes(), 0);
            entries.put(key, entry);
        }
        entry.amount += amount;
        total += amount;
        dirty = true;
        return amount;
    }

    /** Puts stored items back, used when a withdrawal did not fit. */
    void restore(Entry entry, long amount) {
        if (amount <= 0) return;
        entry.amount += amount;
        total += amount;
        entries.putIfAbsent(entry.key, entry);
        dirty = true;
    }

    /** Takes up to {@code amount} of a kind of item out. */
    long take(Entry entry, long amount) {
        long taken = Math.min(amount, entry.amount);
        if (taken <= 0) return 0;
        entry.amount -= taken;
        total -= taken;
        if (entry.amount == 0) entries.remove(entry.key);
        dirty = true;
        return taken;
    }

    /** Used when loading from the database. */
    void load(byte[] bytes, long amount) {
        ItemStack key = ItemStack.deserializeBytes(bytes).asOne();
        entries.put(key, new Entry(key, bytes, amount));
        total += amount;
    }
}
