package in.jolt.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class Jdbc {

    private Jdbc() {}

    static <T> List<T> query(
            Connection c, int timeout, String sql, RowMapper<T> mapper, Object[] args)
            throws SQLException {
        try (PreparedStatement ps = bind(c, timeout, sql, args);
                var rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapper.map(rs));
            return out;
        }
    }

    static <T> Optional<T> queryOne(
            Connection c, int timeout, String sql, RowMapper<T> mapper, Object[] args)
            throws SQLException {
        try (PreparedStatement ps = bind(c, timeout, sql, args);
                var rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
        }
    }

    static int update(Connection c, int timeout, String sql, Object[] args) throws SQLException {
        try (PreparedStatement ps = bind(c, timeout, sql, args)) {
            return ps.executeUpdate();
        }
    }

    static Optional<Long> insert(Connection c, int timeout, String sql, Object[] args)
            throws SQLException {
        try (PreparedStatement ps = bind(c, timeout, sql, args, true)) {
            if (ps.executeUpdate() == 0) return Optional.empty();
            try (var keys = ps.getGeneratedKeys()) {
                if (!keys.next()) return Optional.empty();
                int columns = keys.getMetaData().getColumnCount();
                for (int i = 1; i <= columns; i++) {
                    if (keys.getObject(i) instanceof Number n) return Optional.of(n.longValue());
                }
                return Optional.empty();
            }
        }
    }

    static void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    static int[] batch(Connection c, int timeout, String sql, List<Object[]> rows, int chunk)
            throws SQLException {
        if (chunk < 1) throw new IllegalArgumentException("chunk size must be at least 1");
        int[] counts = new int[rows.size()];
        if (rows.isEmpty()) return counts;
        try (PreparedStatement ps = bind(c, timeout, sql, new Object[0], false)) {
            int filled = 0;
            int pending = 0;
            for (int r = 0; r < rows.size(); r++) {
                Object[] args = rows.get(r);
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                ps.addBatch();
                if (++pending == chunk || r == rows.size() - 1) {
                    int[] part = ps.executeBatch();
                    System.arraycopy(part, 0, counts, filled, part.length);
                    filled += part.length;
                    pending = 0;
                }
            }
        }
        return counts;
    }

    private static PreparedStatement bind(Connection c, int timeout, String sql, Object[] args)
            throws SQLException {
        return bind(c, timeout, sql, args, false);
    }

    private static PreparedStatement bind(
            Connection c, int timeout, String sql, Object[] args, boolean keys)
            throws SQLException {
        PreparedStatement ps =
                keys
                        ? c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                        : c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(timeout);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }
}
