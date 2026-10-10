package dev.magnet;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every collector, indexed by world and chunk. There is at most one collector per chunk, so finding the collector
 * for a dropped item is two hash lookups. Main thread only.
 */
public final class Collectors {

    private final Storage storage;
    private final Map<UUID, Map<Long, Collector>> byChunk = new HashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    Collectors(Storage storage) {
        this.storage = storage;
    }

    void load() throws Exception {
        for (Storage.Snapshot s : storage.loadAll()) {
            Collector collector = new Collector(s.id(), s.world(), s.x(), s.y(), s.z(), s.owner(), s.autosell());
            for (Storage.Row row : s.rows()) collector.load(row.item(), row.amount());
            index(collector);
            nextId.accumulateAndGet(s.id() + 1, Math::max);
        }
    }

    private static long key(int x, int z) {
        return Chunk.getChunkKey(x >> 4, z >> 4);
    }

    private void index(Collector collector) {
        byChunk.computeIfAbsent(collector.world, id -> new HashMap<>()).put(key(collector.x, collector.z), collector);
    }

    public boolean isEmpty() {
        return byChunk.isEmpty();
    }

    /** The collector of the chunk that has this position, called for every item that spawns. */
    public Collector at(World world, double x, double z) {
        Map<Long, Collector> chunks = byChunk.get(world.getUID());
        return chunks == null ? null : chunks.get(key((int) Math.floor(x), (int) Math.floor(z)));
    }

    /** The collector that is this exact block, null if the block is anything else. */
    public Collector at(Block block) {
        Collector collector = at(block.getWorld(), block.getX(), block.getZ());
        return collector != null && collector.x == block.getX() && collector.y == block.getY() && collector.z == block.getZ() ? collector : null;
    }

    public Collector create(Block block, UUID owner) {
        Collector collector = new Collector(nextId.getAndIncrement(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), owner, false);
        collector.dirty = true;
        index(collector);
        return collector;
    }

    public void remove(Collector collector) {
        Map<Long, Collector> chunks = byChunk.get(collector.world);
        if (chunks != null) {
            chunks.remove(key(collector.x, collector.z));
            if (chunks.isEmpty()) byChunk.remove(collector.world);
        }
        storage.delete(collector.id);
    }

    public List<Collector> all() {
        List<Collector> all = new ArrayList<>();
        for (Map<Long, Collector> chunks : byChunk.values()) all.addAll(chunks.values());
        return all;
    }

    /** Writes the collectors that changed since the last save. The saving itself happens off the main thread. */
    public void save() {
        Set<Integer> failed = storage.takeFailed();
        List<Storage.Snapshot> changed = new ArrayList<>();
        for (Map<Long, Collector> chunks : byChunk.values()) {
            for (Collector c : chunks.values()) {
                if (!c.dirty && !failed.contains(c.id)) continue;
                c.dirty = false;
                changed.add(snapshot(c));
            }
        }
        storage.save(changed);
    }

    /**
     * Writes one collector and waits until it is on disk. Used after items leave it: if the server crashed after a
     * player's inventory was saved but before this was, the items would be in both places.
     */
    public void saveNow(Collector c) {
        if (!c.dirty) return;
        c.dirty = false;
        if (!storage.saveNow(snapshot(c))) c.dirty = true;
    }

    /**
     * Writes the collector as it is now, with the record that a sale started, in one step. Call it after the items
     * were taken and before the money is paid.
     *
     * @return the record id, null if it could not be written (then the sale must not go ahead)
     */
    String beginSale(Collector c, String detail) {
        String record = storage.beginSale(snapshot(c), detail);
        if (record != null) c.dirty = false;
        return record;
    }

    void saleDone(String record) {
        storage.saleDone(record);
    }

    /** The payment was refused and the items are back in the collector: written with the record. */
    void saleRefused(Collector c, String record, String reason) {
        c.dirty = false;
        if (!storage.saleRefused(snapshot(c), record, reason)) c.dirty = true;
    }

    void saleUnknown(String record, String reason) {
        storage.saleUnknown(record, reason);
    }

    private static Storage.Snapshot snapshot(Collector c) {
        List<Storage.Row> rows = new ArrayList<>(c.entries.size());
        for (Collector.Entry e : c.entries.values()) rows.add(new Storage.Row(e.bytes, e.amount));
        return new Storage.Snapshot(c.id, c.world, c.x, c.y, c.z, c.owner, c.autosell, rows);
    }

    void close() {
        save();
        storage.close();
    }
}
