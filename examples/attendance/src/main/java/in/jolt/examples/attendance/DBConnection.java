package in.jolt.examples.attendance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

final class DBConnection {

    private static final String DB_URL =
            "jdbc:sqlite:" + System.getProperty("jolt.db", "attendance.db");

    private DBConnection() {}

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    /** Creates the schema and a demo student ({@code student}/{@code student}) when empty. */
    static void init() throws SQLException {
        try (Connection c = getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS credentials "
                            + "(user_id TEXT PRIMARY KEY, password TEXT NOT NULL)");
            s.execute("INSERT OR IGNORE INTO credentials VALUES ('student', 'student')");
        }
    }
}
