package in.jolt.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookingTest {

    @TempDir Path dir;
    String url;
    Db db;

    @BeforeEach
    void open() {
        url = "jdbc:sqlite:" + dir.resolve("b.db");
        db = Db.open(url + "?busy_timeout=10000");
        db.update("create table t(id integer primary key, name text unique)");
        db.update(
                "create table seats(id integer primary key, status text not null,"
                        + " version integer not null)");
        db.update("create table bookings(id integer primary key, seat integer, who text)");
        db.update("insert into seats(id, status, version) values (1, 'FREE', 0)");
    }

    @AfterEach
    void close() {
        db.close();
    }

    int count(String table) {
        return db.queryOne("select count(*) from " + table, rs -> rs.getInt(1)).orElseThrow();
    }

    @Test
    void insertReturnsGeneratedKey() {
        assertEquals(Optional.of(1L), db.insert("insert into t(name) values (?)", "a"));
        assertEquals(Optional.of(2L), db.insert("insert into t(name) values (?)", "b"));
        assertEquals(
                Optional.of(3L),
                db.inTransaction(tx -> tx.insert("insert into t(name) values (?)", "c")));
        assertEquals(0, db.inUse());
    }

    @Test
    void insertFailureReleasesConnection() {
        db.insert("insert into t(name) values (?)", "a");
        assertThrows(DbException.class, () -> db.insert("insert into t(name) values (?)", "a"));
        assertEquals(0, db.inUse());
    }

    @Test
    void batchInsertsAllRowsAndReturnsCounts() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1200; i++) rows.add(new Object[] {"n" + i});
        int[] counts = db.batch("insert into t(name) values (?)", rows);
        assertEquals(1200, counts.length);
        assertEquals(1, counts[0]);
        assertEquals(1200, count("t"));
        assertEquals(0, db.inUse());
    }

    @Test
    void batchIsAllOrNothing() {
        db.update("insert into t(name) values (?)", "dup");
        List<Object[]> rows = List.of(new Object[] {"x"}, new Object[] {"dup"}, new Object[] {"y"});
        assertThrows(DbException.class, () -> db.batch("insert into t(name) values (?)", rows));
        assertEquals(1, count("t"));
        assertEquals(0, db.inUse());
    }

    @Test
    void batchChunksStayInOneTransaction() {
        List<Object[]> rows = List.of(new Object[] {"a"}, new Object[] {"b"}, new Object[] {"c"});
        assertArrayEquals(new int[] {1, 1, 1}, db.batch("insert into t(name) values (?)", rows, 2));
        List<Object[]> bad = List.of(new Object[] {"d"}, new Object[] {"e"}, new Object[] {"a"});
        assertThrows(DbException.class, () -> db.batch("insert into t(name) values (?)", bad, 1));
        assertEquals(3, count("t"));
        assertThrows(
                IllegalArgumentException.class,
                () -> db.batch("insert into t(name) values (?)", rows, 0));
    }

    @Test
    void emptyBatchDoesNothingAndTxViewBatches() {
        assertEquals(0, db.batch("insert into t(name) values (?)", List.of()).length);
        db.inTransaction(
                tx ->
                        tx.batch(
                                "insert into t(name) values (?)",
                                List.<Object[]>of(new Object[] {"q"})));
        assertEquals(1, count("t"));
    }

    @Test
    void immediateTransactionCommitsAndRollsBack() {
        db.inTransaction(TxMode.IMMEDIATE, tx -> tx.update("insert into t(name) values (?)", "a"));
        assertEquals(1, count("t"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        db.inTransaction(
                                TxMode.IMMEDIATE,
                                tx -> {
                                    tx.update("insert into t(name) values (?)", "b");
                                    throw new IllegalStateException("boom");
                                }));
        assertEquals(1, count("t"));
        assertEquals(0, db.inUse());
        db.inTransaction(TxMode.DEFERRED, tx -> tx.update("insert into t(name) values (?)", "c"));
        assertEquals(2, count("t"));
    }

    @Test
    void immediateTransactionTakesWriteLockAtBegin() throws Exception {
        try (Db other = Db.open(url, 2, Duration.ofSeconds(2)).busyRetries(0)) {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            db.inTransaction(
                                                    TxMode.IMMEDIATE,
                                                    tx -> {
                                                        held.countDown();
                                                        release.await();
                                                        return null;
                                                    }));
            held.await();
            var e =
                    assertThrows(
                            DbException.class,
                            () -> other.inTransaction(TxMode.IMMEDIATE, tx -> "never"));
            assertTrue(e.getMessage().contains("busy"), e.getMessage());
            assertEquals(0, other.inUse());
            release.countDown();
            holder.join();
            assertEquals("ok", other.inTransaction(TxMode.IMMEDIATE, tx -> "ok"));
        }
    }

    @Test
    void immediateTransactionRetriesWhileBusy() throws Exception {
        try (Db other = Db.open(url, 2, Duration.ofSeconds(2)).busyRetries(6)) {
            CountDownLatch held = new CountDownLatch(1);
            Thread holder =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            db.inTransaction(
                                                    TxMode.IMMEDIATE,
                                                    tx -> {
                                                        held.countDown();
                                                        Thread.sleep(150);
                                                        return null;
                                                    }));
            held.await();
            assertEquals("ok", other.inTransaction(TxMode.IMMEDIATE, tx -> "ok"));
            holder.join();
            assertEquals(0, other.inUse());
        }
    }

    @Test
    void busyRetriesMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> db.busyRetries(-1));
    }

    @Test
    void detectsBusyAndLockedErrors() {
        assertTrue(Db.isBusy(new SQLException("x", "S", 5)));
        assertTrue(Db.isBusy(new SQLException("x", "S", 6)));
        assertTrue(Db.isBusy(new SQLException("x", "S", 261)));
        assertTrue(Db.isBusy(new SQLException("[SQLITE_BUSY] database is locked")));
        assertTrue(Db.isBusy(new SQLException("[SQLITE_LOCKED] table is locked")));
        assertTrue(Db.isBusy(new RuntimeException(new SQLException("database is locked"))));
        assertFalse(Db.isBusy(new SQLException("constraint", "S", 19)));
        assertFalse(Db.isBusy(new RuntimeException("other")));
    }

    @Test
    void immediateFallsBackToDefaultOnOtherDatabases() {
        AtomicInteger commits = new AtomicInteger();
        Db fake =
                Db.open(
                        () -> {
                            Connection real = DriverManager.getConnection(url);
                            return (Connection)
                                    Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {Connection.class},
                                            (p, m, a) -> {
                                                if (m.getName().equals("getMetaData")) {
                                                    return Proxy.newProxyInstance(
                                                            getClass().getClassLoader(),
                                                            new Class<?>[] {DatabaseMetaData.class},
                                                            (p2, m2, a2) -> "OtherDb");
                                                }
                                                if (m.getName().equals("commit")) {
                                                    commits.incrementAndGet();
                                                }
                                                return DbResilienceTest.call(real, m, a);
                                            });
                        },
                        1,
                        Duration.ofSeconds(1));
        try (fake) {
            fake.inTransaction(
                    TxMode.IMMEDIATE, tx -> tx.update("insert into t(name) values (?)", "z"));
        }
        assertEquals(1, commits.get());
        assertEquals(1, count("t"));
    }

    @Test
    void updateExpectingPassesWhenRowCountMatches() {
        db.inTransaction(
                tx -> {
                    tx.updateExpecting(
                            "update seats set status = 'BOOKED', version = version + 1"
                                    + " where id = ? and version = ?",
                            1,
                            1,
                            0);
                    return null;
                });
        assertEquals(
                "BOOKED", db.queryOne("select status from seats", rs -> rs.getString(1)).get());
    }

    @Test
    void updateExpectingFailsTypedAndRollsBack() {
        var e =
                assertThrows(
                        OptimisticLockException.class,
                        () ->
                                db.inTransaction(
                                        tx -> {
                                            tx.update("insert into t(name) values (?)", "kept?");
                                            tx.updateExpecting(
                                                    "update seats set version = version + 1"
                                                            + " where id = ? and version = ?",
                                                    1,
                                                    1,
                                                    99);
                                            return null;
                                        }));
        assertInstanceOf(DbException.class, e);
        assertTrue(e.getMessage().contains("expected 1"), e.getMessage());
        assertTrue(e.getMessage().contains("0"), e.getMessage());
        assertEquals(0, count("t"));
        assertEquals(0, db.inUse());
    }

    @Test
    void twoThreadsBookTheSameSeatOnce() throws Exception {
        assertEquals(1, race(2));
    }

    @Test
    void fiftyThreadsBookTheSameSeatOnce() throws Exception {
        assertEquals(1, race(50));
    }

    int race(int threads) throws Exception {
        AtomicInteger won = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String who = "user" + i;
                fs.add(
                        ex.submit(
                                () -> {
                                    go.await();
                                    try {
                                        db.inTransaction(TxMode.IMMEDIATE, tx -> book(tx, who));
                                        won.incrementAndGet();
                                    } catch (OptimisticLockException | SeatTaken lost) {
                                        // expected for every thread but one
                                    }
                                    return null;
                                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        }
        assertEquals(1, count("bookings"));
        assertEquals(
                "BOOKED", db.queryOne("select status from seats", rs -> rs.getString(1)).get());
        assertEquals(1, db.queryOne("select version from seats", rs -> rs.getInt(1)).get());
        assertEquals(0, db.inUse());
        return won.get();
    }

    static final class SeatTaken extends RuntimeException {
        SeatTaken() {
            super("seat taken");
        }
    }

    static Object book(Sql tx, String who) {
        int version =
                tx.queryOne(
                                "select version from seats where id = ? and status = 'FREE'",
                                rs -> rs.getInt(1),
                                1)
                        .orElseThrow(SeatTaken::new);
        tx.updateExpecting(
                "update seats set status = 'BOOKED', version = version + 1"
                        + " where id = ? and version = ?",
                1,
                1,
                version);
        tx.insert("insert into bookings(seat, who) values (?, ?)", 1, who);
        return null;
    }
}
