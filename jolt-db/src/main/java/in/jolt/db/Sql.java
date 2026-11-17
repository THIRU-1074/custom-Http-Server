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
}
