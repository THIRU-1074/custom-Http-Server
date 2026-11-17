package in.jolt.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbTest {

    @TempDir Path dir;
    Db db;

    @BeforeEach
    void open() {
        db = Db.open("jdbc:sqlite:" + dir.resolve("t.db") + "?busy_timeout=5000");
        db.update("create table t(id integer primary key, name text)");
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void queriesAndUpdates() {
        assertEquals(1, db.update("insert into t(name) values (?)", "a"));
        db.update("insert into t(name) values (?)", "b");
        List<String> names = db.query("select name from t order by id", rs -> rs.getString(1));
        assertEquals(List.of("a", "b"), names);
        assertEquals(
                Optional.of("b"),
                db.queryOne("select name from t where id = ?", rs -> rs.getString(1), 2));
        assertEquals(
                Optional.empty(),
                db.queryOne("select name from t where id = ?", rs -> rs.getString(1), 99));
        assertEquals(1, db.update("delete from t where name = ?", "a"));
        assertEquals(0, db.inUse());
    }

    @Test
    void bindsParametersInsteadOfConcatenating() {
        String evil = "x'; drop table t; --";
        db.update("insert into t(name) values (?)", evil);
        assertEquals(
                1, db.query("select id from t where name = ?", rs -> rs.getInt(1), evil).size());
    }

    @Test
    void transactionCommits() {
        int n =
                db.inTransaction(
                        tx -> {
                            tx.update("insert into t(name) values (?)", "a");
                            return tx.update("insert into t(name) values (?)", "b");
                        });
        assertEquals(1, n);
        assertEquals(2, db.query("select id from t", rs -> rs.getInt(1)).size());
        assertEquals(0, db.inUse());
    }

    @Test
    void transactionRollsBackOnException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        db.inTransaction(
                                tx -> {
                                    tx.update("insert into t(name) values (?)", "a");
                                    throw new IllegalStateException("boom");
                                }));
        assertEquals(0, db.query("select id from t", rs -> rs.getInt(1)).size());
        assertEquals(0, db.inUse());
    }

    @Test
    void sqlErrorsAreUncheckedAndReleaseConnection() {
        assertThrows(DbException.class, () -> db.query("select * from missing", rs -> 1));
        assertEquals(0, db.inUse());
    }

    @Test
    void poolIsBoundedAndTimesOut() throws Exception {
        try (var small = Db.open("jdbc:sqlite:" + dir.resolve("p.db"), 1, Duration.ofMillis(200))) {
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Thread t =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            small.inTransaction(
                                                    tx -> {
                                                        held.countDown();
                                                        release.await();
                                                        return null;
                                                    }));
            held.await();
            assertEquals(1, small.inUse());
            var e = assertThrows(DbException.class, () -> small.query("select 1", rs -> 1));
            assertTrue(e.getMessage().contains("timed out"));
            release.countDown();
            t.join();
            assertEquals(0, small.inUse());
            assertEquals(1, small.query("select 1", rs -> 1).size());
        }
    }

    @Test
    void concurrentUseFromVirtualThreads() throws Exception {
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int k = i;
                fs.add(ex.submit(() -> db.update("insert into t(name) values (?)", "n" + k)));
                fs.add(ex.submit(() -> db.query("select count(*) from t", rs -> rs.getInt(1))));
            }
            for (Future<?> f : fs) f.get();
        }
        assertEquals(200, db.queryOne("select count(*) from t", rs -> rs.getInt(1)).orElseThrow());
        assertEquals(0, db.inUse());
    }

    @Test
    void closedPoolRejectsUse() {
        var other = Db.open("jdbc:sqlite:" + dir.resolve("c.db"));
        other.close();
        assertThrows(DbException.class, () -> other.query("select 1", rs -> 1));
        other.close();
    }
}
