package dev.magnet;

import dev.magnet.safe.Db;
import dev.magnet.safe.DbBackups;
import dev.magnet.safe.Health;
import dev.magnet.safe.Journal;
import dev.magnet.safe.SaveQueue;
import dev.magnet.safe.ServerId;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * SQLite file with a collectors table and an items table. Writes run on one background thread, in order, and the main
 * thread only builds the snapshots that are handed in. The collectors are the players' items, so a database that
 * cannot be read is never replaced by an empty one.
 */
final class Storage {

    static final int SCHEMA = 1;
    private static final String BACKUP_PREFIX = "collectors";
    private static final long WAIT_SECONDS = 15;

    record Row(byte[] item, long amount) {}

    /** A collector as it is written to disk. */
    record Snapshot(int id, UUID world, int x, int y, int z, UUID owner, boolean autosell, List<Row> rows) {}

    private final Logger log;
    private final Path file;
    private final Path backupDir;
    private Db db;
    private Journal journal;
    private DbBackups backups;
    private SaveQueue saves;
    /** Collectors whose last write failed, so the next save tries them again. */
    private final Set<Integer> failed = ConcurrentHashMap.newKeySet();

    Storage(File file, Logger log) {
        this.file = file.toPath().toAbsolutePath();
        this.backupDir = this.file.getParent().resolve("backups");
        this.log = log;
    }

    /**
     * Opens the database. A damaged one is replaced by the newest backup that verifies; with none the exception is
     * passed on and the file is left as it is.
     */
    void open(int keep) throws IOException, SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite driver not found", ex);
        }
        db = Db.openRecovering(file, SCHEMA, backupDir, BACKUP_PREFIX, true, log);
        try {
            db.tx(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS collectors (id INTEGER PRIMARY KEY, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, owner TEXT NOT NULL, autosell INTEGER NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS items (collector INTEGER NOT NULL, item BLOB NOT NULL, amount INTEGER NOT NULL)");
                    s.execute("CREATE INDEX IF NOT EXISTS items_collector ON items (collector)");
                    s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                }
            });
            if (db.userVersion() < SCHEMA) db.setUserVersion(SCHEMA);
            journal = new Journal(db);
            journal.recover(log);
        } catch (SQLException | RuntimeException e) {
            db.close();
            db = null;
            throw e;
        }
        saves = new SaveQueue("Magnet", 256, log);
        configureBackups(keep);
        Health.file("collectors.db", "in use");
    }

    /** Reads everything. */
    List<Snapshot> loadAll() throws SQLException {
        return db.read(c -> {
            List<Snapshot> all = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, world, x, y, z, owner, autosell FROM collectors")) {
                while (rs.next()) {
                    all.add(new Snapshot(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            UUID.fromString(rs.getString(6)), rs.getInt(7) == 1, new ArrayList<>()));
                }
            }
            try (PreparedStatement s = c.prepareStatement("SELECT item, amount FROM items WHERE collector = ?")) {
                for (Snapshot snapshot : all) {
                    s.setInt(1, snapshot.id());
                    try (ResultSet rs = s.executeQuery()) {
                        while (rs.next()) snapshot.rows().add(new Row(rs.getBytes(1), rs.getLong(2)));
                    }
                }
            }
            return all;
        });
    }

    private static void write(Connection c, Snapshot s) throws SQLException {
        try (PreparedStatement collector = c.prepareStatement("REPLACE INTO collectors (id, world, x, y, z, owner, autosell) VALUES (?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement clear = c.prepareStatement("DELETE FROM items WHERE collector = ?");
             PreparedStatement item = c.prepareStatement("INSERT INTO items (collector, item, amount) VALUES (?, ?, ?)")) {
            collector.setInt(1, s.id());
            collector.setString(2, s.world().toString());
            collector.setInt(3, s.x());
            collector.setInt(4, s.y());
            collector.setInt(5, s.z());
            collector.setString(6, s.owner().toString());
            collector.setInt(7, s.autosell() ? 1 : 0);
            collector.executeUpdate();
            clear.setInt(1, s.id());
            clear.executeUpdate();
            for (Row row : s.rows()) {
                item.setInt(1, s.id());
                item.setBytes(2, row.item());
                item.setLong(3, row.amount());
                item.addBatch();
            }
            item.executeBatch();
        }
    }

    /** Writes the snapshots in the background, all of them in one transaction. */
    void save(List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        try {
            saves.ordered(() -> writeAll(snapshots));
        } catch (IllegalStateException ex) {
            for (Snapshot s : snapshots) failed.add(s.id());
            log.severe("Could not queue the collectors for saving, will try again: " + ex.getMessage());
        }
    }

    private void writeAll(List<Snapshot> snapshots) {
        try {
            db.tx(c -> {
                for (Snapshot s : snapshots) write(c, s);
            });
        } catch (SQLException | RuntimeException ex) {
            for (Snapshot s : snapshots) failed.add(s.id());
            Health.failure("collectors could not be saved: " + ex.getMessage());
            log.severe("Could not save collectors, will try again: " + ex.getMessage());
        }
    }

    /** Writes one collector and waits until it is on disk. @return false if it could not be written */
    boolean saveNow(Snapshot snapshot) {
        try {
            return submit(() -> {
                db.tx(c -> write(c, snapshot));
                return Boolean.TRUE;
            });
        } catch (SQLException | RuntimeException ex) {
            failed.add(snapshot.id());
            Health.failure("a collector could not be saved: " + ex.getMessage());
            log.severe("Could not save a collector, will try again: " + ex.getMessage());
            return false;
        }
    }

    private interface Job<T> {
        T run() throws SQLException;
    }

    /** Runs a job in the order of the writes and waits for it. */
    private <T> T submit(Job<T> job) throws SQLException {
        CompletableFuture<T> done = new CompletableFuture<>();
        try {
            saves.ordered(() -> {
                try {
                    done.complete(job.run());
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            return done.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SQLException sql) throw sql;
            throw new SQLException(String.valueOf(cause), cause);
        } catch (TimeoutException ex) {
            throw new SQLException("the database did not answer in " + WAIT_SECONDS + " seconds");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for the database");
        } catch (IllegalStateException ex) {
            throw new SQLException(ex.getMessage());
        }
    }

    /** The collectors whose write failed since the last call. */
    Set<Integer> takeFailed() {
        Set<Integer> out = new HashSet<>(failed);
        failed.removeAll(out);
        return out;
    }

    void delete(int id) {
        try {
            saves.ordered(() -> {
                try {
                    db.tx(c -> {
                        try (PreparedStatement a = c.prepareStatement("DELETE FROM items WHERE collector = ?");
                             PreparedStatement b = c.prepareStatement("DELETE FROM collectors WHERE id = ?")) {
                            a.setInt(1, id);
                            a.executeUpdate();
                            b.setInt(1, id);
                            b.executeUpdate();
                        }
                    });
                } catch (SQLException ex) {
                    log.severe("Could not delete a collector: " + ex.getMessage());
                }
            });
        } catch (IllegalStateException ex) {
            log.severe("Could not queue the removal of a collector: " + ex.getMessage());
        }
    }

    // ---- sales: the record of money that moves

    /**
     * Writes the collector without the items that are being sold, and the record that the sale started, in one
     * transaction. Only after this is the money paid.
     *
     * @return the record id, or null if nothing could be written (then nothing is sold)
     */
    String beginSale(Snapshot after, String detail) {
        String id = UUID.randomUUID().toString();
        try {
            submit(() -> {
                db.tx(c -> {
                    write(c, after);
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO op_journal (id, kind, state, detail, started_at) VALUES (?, 'sale', 'pending', ?, ?)")) {
                        ps.setString(1, id);
                        ps.setString(2, detail);
                        ps.setLong(3, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                });
                return Boolean.TRUE;
            });
            return id;
        } catch (SQLException | RuntimeException ex) {
            failed.add(after.id());
            Health.failure("a sale could not be recorded: " + ex.getMessage());
            log.severe("Could not record a sale, so nothing was sold: " + ex.getMessage());
            return null;
        }
    }

    void saleDone(String record) {
        try {
            journal.succeeded(record);
        } catch (SQLException ex) {
            log.warning("The sale record could not be finished: " + ex.getMessage());
        }
    }

    /** The economy refused the payment: the items are back in the collector, written together with the record. */
    boolean saleRefused(Snapshot restored, String record, String reason) {
        try {
            submit(() -> {
                db.tx(c -> {
                    write(c, restored);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE op_journal SET state='failed', finished_at=?, detail=COALESCE(detail,'') || ? WHERE id=? AND state='pending'")) {
                        ps.setLong(1, System.currentTimeMillis());
                        ps.setString(2, " (" + reason + ")");
                        ps.setString(3, record);
                        ps.executeUpdate();
                    }
                });
                return Boolean.TRUE;
            });
            return true;
        } catch (SQLException | RuntimeException ex) {
            failed.add(restored.id());
            log.severe("The items of a refused sale could not be written back yet, they stay in memory and are tried again: " + ex.getMessage());
            try {
                journal.failed(record, reason);
            } catch (SQLException ignored) {
                // listed as unchecked after a restart, the log has the details
            }
            return false;
        }
    }

    /** The outcome of the payment is not known: it is listed for a person and never repeated. */
    void saleUnknown(String record, String reason) {
        try {
            journal.flag(record, reason);
        } catch (SQLException ex) {
            log.warning("The sale record could not be updated: " + ex.getMessage());
        }
    }

    // ---- care

    void configureBackups(int keep) {
        if (db != null) backups = new DbBackups(db, backupDir, BACKUP_PREFIX, keep, log);
    }

    boolean backup() {
        return backups == null || backups.run();
    }

    Journal journal() {
        return journal;
    }

    int schemaVersion() {
        try {
            return db == null ? SCHEMA : db.userVersion();
        } catch (SQLException e) {
            return -1;
        }
    }

    String newestBackup() {
        List<Path> found = DbBackups.list(backupDir, BACKUP_PREFIX);
        return found.isEmpty() ? null : found.get(0).getFileName().toString();
    }

    int pendingWrites() {
        return saves == null ? 0 : saves.pending();
    }

    long failedWrites() {
        return saves == null ? 0 : saves.failedSaves();
    }

    ServerId.Slot serverIdSlot() {
        return new ServerId.Slot() {
            @Override
            public String read() throws SQLException {
                return db.read(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key='server-id'");
                         ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                });
            }

            @Override
            public void write(String id) throws SQLException {
                db.tx(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO meta (key, value) VALUES ('server-id', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                });
            }
        };
    }

    /** Waits for the queued writes, then closes. */
    void close() {
        if (saves != null && !saves.shutdown(30_000)) {
            log.warning("Saving collectors took too long, some changes may be lost.");
        }
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
