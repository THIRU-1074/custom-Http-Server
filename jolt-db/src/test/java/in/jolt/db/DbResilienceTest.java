package in.jolt.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbResilienceTest {

    @TempDir Path dir;

    final List<Flaky> opened = new ArrayList<>();
    final List<Integer> timeouts = new ArrayList<>();
    Db db;

    class Flaky {
        final Connection real;
        volatile boolean invalid;
        volatile boolean failStatements;
        volatile boolean failRollback;
        final AtomicInteger closes = new AtomicInteger();
        Connection proxy;

        Flaky(Connection real) {
            this.real = real;
        }
    }

    Connection flaky() throws SQLException {
        Flaky f = new Flaky(DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("r.db")));
        opened.add(f);
        f.proxy =
                (Connection)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {Connection.class},
                                (p, m, a) -> {
                                    switch (m.getName()) {
                                        case "isValid" -> {
                                            if (f.invalid) return false;
                                        }
                                        case "prepareStatement" -> {
                                            if (f.failStatements) throw new SQLException("broken");
                                            return recording(
                                                    (PreparedStatement) call(f.real, m, a));
                                        }
                                        case "rollback" -> {
                                            if (f.failRollback)
                                                throw new SQLException("no rollback");
                                        }
                                        case "close" -> f.closes.incrementAndGet();
                                        default -> {}
                                    }
                                    return call(f.real, m, a);
                                });
        return f.proxy;
    }

    PreparedStatement recording(PreparedStatement ps) {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PreparedStatement.class},
                        (p, m, a) -> {
                            if (m.getName().equals("setQueryTimeout")) timeouts.add((Integer) a[0]);
                            return call(ps, m, a);
                        });
    }

    static Object call(Object target, java.lang.reflect.Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @BeforeEach
    void open() {
        db = Db.open(this::flaky, 2, Duration.ofSeconds(1));
        db.update("create table if not exists t(id integer primary key, name text)");
    }

    @AfterEach
    void close() {
        db.close();
    }

    int rows() {
        return db.queryOne("select count(*) from t", rs -> rs.getInt(1)).orElseThrow();
    }

    @Test
    void errorAfterUpdateLeavesNothingCommitted() {
        assertThrows(
                AssertionError.class,
                () ->
                        db.inTransaction(
                                tx -> {
                                    tx.update("insert into t(name) values (?)", "a");
                                    throw new AssertionError("fatal");
                                }));
        assertEquals(0, rows());
        assertEquals(0, db.inUse());
    }

    @Test
    void interruptFromTxRestoresFlag() {
        assertThrows(
                DbException.class,
                () ->
                        db.inTransaction(
                                tx -> {
                                    throw new InterruptedException("stop");
                                }));
        assertTrue(Thread.interrupted());
    }

    @Test
    void failingRollbackDiscardsConnection() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        db.inTransaction(
                                tx -> {
                                    opened.get(0).failRollback = true;
                                    throw new IllegalStateException("x");
                                }));
        Flaky bad = opened.get(0);
        assertEquals(1, bad.closes.get());
        assertEquals(0, db.inUse());
        assertEquals(0, rows());
        assertEquals(2, opened.size());
    }

    @Test
    void sqlFailureEvictsConnection() {
        Flaky first = opened.get(0);
        first.failStatements = true;
        assertThrows(DbException.class, () -> db.query("select 1", rs -> 1));
        assertEquals(1, first.closes.get());
        assertEquals(1, db.query("select 1", rs -> 1).size());
        assertEquals(2, opened.size());
    }

    @Test
    void invalidIdleConnectionIsReplacedBeforeUse() {
        Flaky first = opened.get(0);
        first.invalid = true;
        assertEquals(1, db.query("select 1", rs -> 1).size());
        assertEquals(1, first.closes.get());
        assertEquals(2, opened.size());
    }

    @Test
    void statementTimeoutIsApplied() {
        timeouts.clear();
        db.query("select 1", rs -> 1);
        assertEquals(List.of(30), timeouts);
        timeouts.clear();
        db.queryTimeout(Duration.ofSeconds(7));
        db.queryOne("select 1", rs -> 1);
        db.update("delete from t");
        db.inTransaction(tx -> tx.update("delete from t"));
        assertEquals(List.of(7, 7, 7), timeouts);
    }
}
