package in.jolt.examples.webhook;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

final class DBConnection {

    private static final String DB_URL =
            "jdbc:sqlite:" + System.getProperty("jolt.db", "webhooks.db");

    private DBConnection() {}

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    static void init() throws SQLException {
        try (Connection c = getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS webhookURLs "
                            + "(userName TEXT NOT NULL, url TEXT NOT NULL)");
        }
    }
}
