package in.jolt.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class Jdbc {

    private Jdbc() {}

    static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object[] args)
            throws SQLException {
        try (PreparedStatement ps = bind(c, sql, args);
                var rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapper.map(rs));
            return out;
        }
    }

    static <T> Optional<T> queryOne(Connection c, String sql, RowMapper<T> mapper, Object[] args)
            throws SQLException {
        try (PreparedStatement ps = bind(c, sql, args);
                var rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
        }
    }

    static int update(Connection c, String sql, Object[] args) throws SQLException {
        try (PreparedStatement ps = bind(c, sql, args)) {
            return ps.executeUpdate();
        }
    }

    private static PreparedStatement bind(Connection c, String sql, Object[] args)
            throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }
}
