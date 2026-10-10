package dev.magnet;

import dev.magnet.safe.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path dir;

    private File file() {
        return dir.resolve("collectors.db").toFile();
    }

    private Storage open() throws Exception {
        Storage storage = new Storage(file(), LOG);
        storage.open(3);
        return storage;
    }

    private static Storage.Snapshot collector(int id, long cobble) {
        List<Storage.Row> rows = cobble == 0 ? List.of() : List.of(new Storage.Row(new byte[]{1, 2, 3}, cobble));
        return new Storage.Snapshot(id, WORLD, id * 16, 64, id * 16, STEVE, false, rows);
    }

    @Test
    void savedCollectorsComeBackAfterARestart() throws Exception {
        Storage first = open();
        first.save(List.of(collector(1, 500), collector(2, 7)));
        assertTrue(first.saveNow(collector(3, 1)));
        first.close();

        Storage again = open();
        List<Storage.Snapshot> all = again.loadAll();
        assertEquals(3, all.size());
        assertEquals(500, all.stream().filter(s -> s.id() == 1).findFirst().orElseThrow().rows().get(0).amount());
        again.close();
    }

    @Test
    void aRemovedCollectorStaysRemoved() throws Exception {
        Storage first = open();
        first.save(List.of(collector(1, 5), collector(2, 5)));
        first.delete(1);
        first.close();
        Storage again = open();
        assertEquals(1, again.loadAll().size());
        again.close();
    }

    @Test
    void aDamagedDatabaseIsNotReplacedByAnEmptyOne() throws Exception {
        open().close();
        byte[] garbage = "this is not a database, not at all, not one bit".getBytes();
        Files.write(file().toPath(), garbage);
        Storage damaged = new Storage(file(), LOG);
        assertThrows(Db.CorruptException.class, () -> damaged.open(3));
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(file().toPath())));
    }

    @Test
    void aDamagedDatabaseComesBackFromTheNewestGoodBackup() throws Exception {
        Storage first = open();
        assertTrue(first.saveNow(collector(1, 42)));
        assertTrue(first.backup());
        first.close();

        Files.write(file().toPath(), "garbage garbage garbage garbage".getBytes());
        Files.deleteIfExists(dir.resolve("collectors.db-wal"));
        Storage restored = open();
        assertEquals(42, restored.loadAll().get(0).rows().get(0).amount());
        restored.close();
    }

    @Test
    void aDatabaseFromANewerVersionIsNotTouched() throws Exception {
        open().close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file().getAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        byte[] before = Files.readAllBytes(file().toPath());
        Storage newer = new Storage(file(), LOG);
        assertThrows(Db.NewerSchemaException.class, () -> newer.open(3));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file().toPath())));
    }

    @Test
    void aDatabaseFromTheOlderVersionOfThePluginIsKeptAsItIs() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file().getAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE collectors (id INTEGER PRIMARY KEY, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, owner TEXT NOT NULL, autosell INTEGER NOT NULL)");
            s.execute("CREATE TABLE items (collector INTEGER NOT NULL, item BLOB NOT NULL, amount INTEGER NOT NULL)");
            s.execute("INSERT INTO collectors VALUES (9, '" + WORLD + "', 1, 2, 3, '" + STEVE + "', 1)");
            s.execute("INSERT INTO items VALUES (9, x'010203', 1234)");
        }
        Storage storage = open();
        List<Storage.Snapshot> all = storage.loadAll();
        assertEquals(1, all.size());
        assertEquals(1234, all.get(0).rows().get(0).amount());
        assertTrue(all.get(0).autosell());
        storage.close();
    }

    @Test
    void aSaleAndItsRecordAreWrittenTogether() throws Exception {
        Storage first = open();
        assertTrue(first.saveNow(collector(1, 100)));
        String record = first.beginSale(collector(1, 0), "collector=1 items=100 money=50");
        assertNotNull(record);
        first.saleDone(record);
        first.close();

        Storage again = open();
        assertEquals(0, again.loadAll().get(0).rows().size());
        assertTrue(again.journal().unknown().isEmpty());
        again.close();
    }

    @Test
    void aSaleThatWasCutShortIsListedAndNeverRepeated() throws Exception {
        Storage first = open();
        assertTrue(first.saveNow(collector(1, 100)));
        assertNotNull(first.beginSale(collector(1, 0), "collector=1 items=100 money=50"));
        first.close(); // stopped after the items were taken and before the payment was confirmed

        Storage again = open();
        assertEquals(0, again.loadAll().get(0).rows().size(), "the items are not sold again");
        assertEquals(1, again.journal().unknown().size());
        again.close();
    }

    @Test
    void aRefusedPaymentPutsTheItemsBackWithTheRecord() throws Exception {
        Storage first = open();
        assertTrue(first.saveNow(collector(1, 100)));
        String record = first.beginSale(collector(1, 0), "collector=1 items=100 money=50");
        assertTrue(first.saleRefused(collector(1, 100), record, "no money"));
        first.close();

        Storage again = open();
        assertEquals(100, again.loadAll().get(0).rows().get(0).amount());
        assertTrue(again.journal().unknown().isEmpty());
        again.close();
    }

    @Test
    void theServerIdIsKeptInTheDatabase() throws Exception {
        Storage first = open();
        assertNull(first.serverIdSlot().read());
        first.serverIdSlot().write("0a1b2c3d-1111-2222-3333-444455556666");
        first.close();
        Storage again = open();
        assertEquals("0a1b2c3d-1111-2222-3333-444455556666", again.serverIdSlot().read());
        again.close();
    }
}
