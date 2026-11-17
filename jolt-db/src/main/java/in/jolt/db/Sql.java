package in.jolt.db;

import java.util.List;
import java.util.Optional;

/**
 * Parameterized SQL operations. Values are always bound to {@code ?} placeholders, never inlined.
 */
public interface Sql {

    /** Runs a query and maps every row. */
    <T> List<T> query(String sql, RowMapper<T> mapper, Object... args);

    /** Runs a query and maps the first row, if any. */
    <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args);

    /** Runs an insert, update or delete and returns the affected row count. */
    int update(String sql, Object... args);

    /** Returns the first numeric generated key; empty if none, or if nothing was inserted. */
    Optional<Long> insert(String sql, Object... args);

    /** Runs one prepared statement for every row of arguments and returns the per-row counts. */
    int[] batch(String sql, List<Object[]> rows);

    /**
     * Runs an update and throws {@link OptimisticLockException} unless it touched exactly {@code
     * expectedRows}. Inside a transaction the throw rolls everything back.
     */
    default void updateExpecting(String sql, int expectedRows, Object... args) {
        int n = update(sql, args);
        if (n != expectedRows) {
            throw new OptimisticLockException(
                    "expected " + expectedRows + " row(s) updated but got " + n + ": " + sql);
        }
    }
}
