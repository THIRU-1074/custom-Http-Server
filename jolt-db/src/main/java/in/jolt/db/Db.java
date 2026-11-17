package in.jolt.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small bounded JDBC connection pool with parameterized queries only. Callers wait on the pool, not
 * on a pinned carrier, but a driver that synchronizes internally can still pin virtual threads.
 * Close it to release every connection.
 */
public final class Db implements Sql, AutoCloseable {

    public static final int DEFAULT_SIZE = 8;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_BUSY_RETRIES = 5;
    public static final int DEFAULT_BATCH_CHUNK = 500;
    private static final long BUSY_BACKOFF_MS = 10;

    @FunctionalInterface
    interface Factory {
        Connection open() throws SQLException;
    }

    private final Factory factory;
    private final Duration timeout;
    private final Semaphore permits;
    private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int size;
    private volatile int queryTimeoutSeconds = (int) DEFAULT_QUERY_TIMEOUT.toSeconds();
    private volatile int busyRetries = DEFAULT_BUSY_RETRIES;
    private volatile Boolean sqlite;

    private Db(Factory factory, int size, Duration timeout) {
        if (size < 1) throw new IllegalArgumentException("pool size must be at least 1");
        this.factory = factory;
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
        return new Db(() -> DriverManager.getConnection(jdbcUrl), size, timeout);
    }

    static Db open(Factory factory, int size, Duration timeout) {
        return new Db(factory, size, timeout);
    }

    /** Sets the per-statement timeout, 30 seconds by default. Zero disables it. */
    public Db queryTimeout(Duration limit) {
        if (limit.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        this.queryTimeoutSeconds =
                (int)
                        Math.min(
                                Integer.MAX_VALUE,
                                Math.max(limit.toSeconds(), limit.isZero() ? 0 : 1));
        return this;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        int t = queryTimeoutSeconds;
        return withConnection(c -> Jdbc.query(c, t, sql, mapper, args));
    }

    @Override
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        int t = queryTimeoutSeconds;
        return withConnection(c -> Jdbc.queryOne(c, t, sql, mapper, args));
    }

    @Override
    public int update(String sql, Object... args) {
        int t = queryTimeoutSeconds;
        return withConnection(c -> Jdbc.update(c, t, sql, args));
    }

    @Override
    public Optional<Long> insert(String sql, Object... args) {
        int t = queryTimeoutSeconds;
        return withConnection(c -> Jdbc.insert(c, t, sql, args));
    }

    /** Runs the batch in one transaction, so it is all or nothing. */
    @Override
    public int[] batch(String sql, List<Object[]> rows) {
        return batch(sql, rows, DEFAULT_BATCH_CHUNK);
    }

    /** Like {@link #batch(String, List)}, sending {@code chunk} rows per round trip. */
    public int[] batch(String sql, List<Object[]> rows, int chunk) {
        return inTransaction(tx -> ((ConnectionSql) tx).batch(sql, rows, chunk));
    }

    /** Sets how often a busy or locked write lock is retried, 5 by default. Zero disables it. */
    public Db busyRetries(int retries) {
        if (retries < 0) throw new IllegalArgumentException("retries must not be negative");
        this.busyRetries = retries;
        return this;
    }

    /**
     * Runs {@code tx} on one connection and commits. Any throwable rolls back and is rethrown;
     * checked exceptions are wrapped in {@link DbException}. Do not call this pool from inside
     * {@code tx}.
     */
    public <T> T inTransaction(Tx<T> tx) {
        return inTransaction(TxMode.DEFERRED, tx);
    }

    /**
     * Like {@link #inTransaction(Tx)}. {@link TxMode#IMMEDIATE} takes the write lock at begin on
     * SQLite, retrying with backoff while it is busy, then fails with a {@link DbException}. Other
     * databases behave as DEFERRED.
     */
    public <T> T inTransaction(TxMode mode, Tx<T> tx) {
        Connection c = acquire();
        boolean broken = false;
        boolean manual = false;
        boolean begun = false;
        try {
            manual = mode == TxMode.IMMEDIATE && isSqlite(c);
            if (manual) beginImmediate(c);
            else c.setAutoCommit(false);
            begun = true;
            T result = tx.run(new ConnectionSql(c, queryTimeoutSeconds));
            if (manual) exec(c, "COMMIT");
            else c.commit();
            return result;
        } catch (Throwable e) {
            broken = manual ? begun && !rollbackManual(c, e) : !rollback(c, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new DbException("transaction failed: " + e.getMessage(), e);
        } finally {
            release(c, broken);
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
        boolean broken = false;
        try {
            return fn.apply(c);
        } catch (SQLException e) {
            broken = true;
            throw new DbException(e.getMessage(), e);
        } finally {
            release(c, broken);
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
            Connection c;
            while ((c = idle.poll()) != null) {
                if (isUsable(c)) return c;
                closeQuietly(c);
            }
            return factory.open();
        } catch (SQLException | RuntimeException e) {
            permits.release();
            throw e instanceof DbException de
                    ? de
                    : new DbException("cannot open connection: " + e.getMessage(), e);
        }
    }

    private static boolean isUsable(Connection c) {
        try {
            return !c.isClosed() && c.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }

    private void release(Connection c, boolean broken) {
        try {
            if (!broken && !c.getAutoCommit()) {
                c.rollback();
                c.setAutoCommit(true);
            }
            if (broken || closed.get()) closeQuietly(c);
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

    private boolean isSqlite(Connection c) throws SQLException {
        Boolean known = sqlite;
        if (known == null) {
            String product = c.getMetaData().getDatabaseProductName();
            known = product != null && product.toLowerCase(Locale.ROOT).contains("sqlite");
            sqlite = known;
        }
        return known;
    }

    private void beginImmediate(Connection c) throws SQLException {
        int attempt = 0;
        while (true) {
            try {
                exec(c, "BEGIN IMMEDIATE");
                return;
            } catch (SQLException e) {
                if (!isBusy(e)) throw e;
                if (attempt >= busyRetries) {
                    throw new DbException(
                            "database busy: could not take the write lock after "
                                    + attempt
                                    + " retries",
                            e);
                }
                try {
                    Thread.sleep(BUSY_BACKOFF_MS << Math.min(attempt++, 10));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new DbException("interrupted waiting for the write lock", ie);
                }
            }
        }
    }

    static boolean isBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) {
                int code = s.getErrorCode() & 0xFF;
                String m = s.getMessage() == null ? "" : s.getMessage();
                if (code == 5
                        || code == 6
                        || m.contains("SQLITE_BUSY")
                        || m.contains("SQLITE_LOCKED")
                        || m.contains("database is locked")
                        || m.contains("table is locked")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static boolean rollbackManual(Connection c, Throwable cause) {
        try {
            exec(c, "ROLLBACK");
            return true;
        } catch (SQLException e) {
            cause.addSuppressed(e);
            return false;
        }
    }

    private static boolean rollback(Connection c, Throwable cause) {
        try {
            c.rollback();
            return true;
        } catch (SQLException e) {
            cause.addSuppressed(e);
            return false;
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

    private record ConnectionSql(Connection c, int timeout) implements Sql {
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            return wrap(() -> Jdbc.query(c, timeout, sql, mapper, args));
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            return wrap(() -> Jdbc.queryOne(c, timeout, sql, mapper, args));
        }

        @Override
        public int update(String sql, Object... args) {
            return wrap(() -> Jdbc.update(c, timeout, sql, args));
        }

        @Override
        public Optional<Long> insert(String sql, Object... args) {
            return wrap(() -> Jdbc.insert(c, timeout, sql, args));
        }

        @Override
        public int[] batch(String sql, List<Object[]> rows) {
            return batch(sql, rows, DEFAULT_BATCH_CHUNK);
        }

        int[] batch(String sql, List<Object[]> rows, int chunk) {
            return wrap(() -> Jdbc.batch(c, timeout, sql, rows, chunk));
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
