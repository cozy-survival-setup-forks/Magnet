package dev.magnet;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * SQLite file with a collectors table and an items table. Every call runs on one background thread, the main thread
 * only builds the snapshots that are handed in.
 */
final class Storage {

    record Row(byte[] item, long amount) {}

    /** A collector as it is written to disk. */
    record Snapshot(int id, UUID world, int x, int y, int z, UUID owner, boolean autosell, List<Row> rows) {}

    private final Logger log;
    private final String url;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "Magnet-Storage");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    Storage(File file, Logger log) {
        this.url = "jdbc:sqlite:" + file;
        this.log = log;
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("SQLite driver not found", ex);
            }
            connection = DriverManager.getConnection(url);
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS collectors (id INTEGER PRIMARY KEY, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, owner TEXT NOT NULL, autosell INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS items (collector INTEGER NOT NULL, item BLOB NOT NULL, amount INTEGER NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS items_collector ON items (collector)");
            }
        }
        return connection;
    }

    /** Reads everything, blocks until done. */
    List<Snapshot> loadAll() throws Exception {
        return thread.submit(() -> {
            List<Snapshot> all = new ArrayList<>();
            Connection c = connection();
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
        }).get();
    }

    void save(List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        thread.execute(() -> {
            try {
                Connection c = connection();
                c.setAutoCommit(false);
                try (PreparedStatement collector = c.prepareStatement("REPLACE INTO collectors (id, world, x, y, z, owner, autosell) VALUES (?, ?, ?, ?, ?, ?, ?)");
                     PreparedStatement clear = c.prepareStatement("DELETE FROM items WHERE collector = ?");
                     PreparedStatement item = c.prepareStatement("INSERT INTO items (collector, item, amount) VALUES (?, ?, ?)")) {
                    for (Snapshot s : snapshots) {
                        collector.setInt(1, s.id());
                        collector.setString(2, s.world().toString());
                        collector.setInt(3, s.x());
                        collector.setInt(4, s.y());
                        collector.setInt(5, s.z());
                        collector.setString(6, s.owner().toString());
                        collector.setInt(7, s.autosell() ? 1 : 0);
                        collector.addBatch();

                        clear.setInt(1, s.id());
                        clear.addBatch();
                        for (Row row : s.rows()) {
                            item.setInt(1, s.id());
                            item.setBytes(2, row.item());
                            item.setLong(3, row.amount());
                            item.addBatch();
                        }
                    }
                    collector.executeBatch();
                    clear.executeBatch();
                    item.executeBatch();
                    c.commit();
                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                log.severe("Could not save collectors: " + ex.getMessage());
            }
        });
    }

    void delete(int id) {
        thread.execute(() -> {
            try (PreparedStatement a = connection().prepareStatement("DELETE FROM items WHERE collector = ?");
                 PreparedStatement b = connection().prepareStatement("DELETE FROM collectors WHERE id = ?")) {
                a.setInt(1, id);
                a.executeUpdate();
                b.setInt(1, id);
                b.executeUpdate();
            } catch (SQLException ex) {
                log.severe("Could not delete a collector: " + ex.getMessage());
            }
        });
    }

    /** Waits for the queued writes, then closes. */
    void close() {
        thread.shutdown();
        try {
            if (!thread.awaitTermination(30, TimeUnit.SECONDS)) log.warning("Saving collectors took too long, some changes may be lost.");
            if (connection != null) connection.close();
        } catch (InterruptedException | SQLException ex) {
            log.warning("Could not close the database cleanly: " + ex.getMessage());
        }
    }
}
