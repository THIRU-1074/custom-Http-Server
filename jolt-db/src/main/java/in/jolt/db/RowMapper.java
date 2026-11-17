package in.jolt.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the current row of a result set. Do not advance the cursor. */
@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
}
