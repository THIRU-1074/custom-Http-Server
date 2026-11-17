package in.jolt.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small bounded JDBC connection pool with parameterized queries only. Safe for virtual threads: a
 * caller waits on the pool, not on a pinned carrier. Close it to release every connection.
 */
public final class Db implements Sql, AutoCloseable {

    public static final int DEFAULT_SIZE = 8;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final String url;
    private final Duration timeout;
    private final Semaphore permits;
    private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int size;

    private Db(String url, int size, Duration timeout) {
        if (size < 1) throw new IllegalArgumentException("pool size must be at least 1");
        this.url = url;
        this.size = size;
        this.timeout = timeout;
        this.permits = new Semaphore(size, true);
    }

    /** Opens a pool of up to 8 connections that waits up to 5 seconds for a free one. */
    public static Db open(String jdbcUrl) {
        return open(jdbcUrl, DEFAULT_SIZE, DEFAULT_TIMEOUT);
    }

    /**
     * Opens a pool of at most {@code size} connections; acquiring one waits up to {@code timeout}.
     */
    public static Db open(String jdbcUrl, int size, Duration timeout) {
        return new Db(jdbcUrl, size, timeout);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return withConnection(c -> Jdbc.query(c, sql, mapper, args));
    }

    @Override
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        return withConnection(c -> Jdbc.queryOne(c, sql, mapper, args));
    }

    @Override
    public int update(String sql, Object... args) {
        return withConnection(c -> Jdbc.update(c, sql, args));
    }

    /**
     * Runs {@code tx} on one connection and commits. Any exception rolls back and is rethrown;
     * checked ones are wrapped in {@link DbException}. Do not call this pool from inside {@code
     * tx}.
     */
    public <T> T inTransaction(Tx<T> tx) {
        Connection c = acquire();
        try {
            c.setAutoCommit(false);
            T result = tx.run(new ConnectionSql(c));
            c.commit();
            return result;
        } catch (Exception e) {
            rollback(c, e);
            if (e instanceof RuntimeException re) throw re;
            throw new DbException("transaction failed: " + e.getMessage(), e);
        } finally {
            release(c);
        }
    }

    /** Number of connections currently checked out. */
    public int inUse() {
        return size - permits.availablePermits();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Connection c;
        while ((c = idle.poll()) != null) closeQuietly(c);
    }

    private <T> T withConnection(SqlFunction<T> fn) {
        Connection c = acquire();
        try {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new DbException(e.getMessage(), e);
        } finally {
            release(c);
        }
    }

    private Connection acquire() {
        if (closed.get()) throw new DbException("pool is closed");
        try {
            if (!permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new DbException("timed out after " + timeout + " waiting for a connection");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DbException("interrupted waiting for a connection", e);
        }
        try {
            Connection c = idle.poll();
            if (c != null && !c.isClosed()) return c;
            return DriverManager.getConnection(url);
        } catch (SQLException e) {
            permits.release();
            throw new DbException("cannot open connection: " + e.getMessage(), e);
        }
    }

    private void release(Connection c) {
        try {
            if (!c.getAutoCommit()) c.setAutoCommit(true);
            if (closed.get()) closeQuietly(c);
            else idle.add(c);
        } catch (SQLException e) {
            closeQuietly(c);
        } finally {
            permits.release();
        }
        if (closed.get()) {
            Connection stale;
            while ((stale = idle.poll()) != null) closeQuietly(stale);
        }
    }

    private static void rollback(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing left to release
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    private record ConnectionSql(Connection c) implements Sql {
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            return wrap(() -> Jdbc.query(c, sql, mapper, args));
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            return wrap(() -> Jdbc.queryOne(c, sql, mapper, args));
        }

        @Override
        public int update(String sql, Object... args) {
            return wrap(() -> Jdbc.update(c, sql, args));
        }

        private static <T> T wrap(SqlSupplier<T> s) {
            try {
                return s.get();
            } catch (SQLException e) {
                throw new DbException(e.getMessage(), e);
            }
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
