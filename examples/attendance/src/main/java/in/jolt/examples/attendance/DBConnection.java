package in.jolt.examples.attendance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class DBConnection {

    static final int MIN_PASSWORD_LENGTH = 12;

    private static final System.Logger LOG = System.getLogger("attendance");

    private DBConnection() {}

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:sqlite:" + System.getProperty("jolt.db", "attendance.db"));
    }

    /**
     * Creates the schema. While no user exists, seeds one admin from the arguments; without them
     * the app starts with no users.
     *
     * @throws IllegalArgumentException if the password is shorter than 12 characters
     */
    static void init(String adminUser, String adminPassword) throws SQLException {
        try (Connection c = getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS users "
                            + "(user_id TEXT PRIMARY KEY, password_hash TEXT NOT NULL)");
            try (ResultSet rs = s.executeQuery("SELECT 1 FROM users LIMIT 1")) {
                if (rs.next()) {
                    return;
                }
            }
            if (adminUser == null || adminUser.isBlank() || adminPassword == null) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "No users exist; set ADMIN_USER and ADMIN_PASSWORD (12+ chars) and restart to seed one");
                return;
            }
            if (adminPassword.length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException(
                        "ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO users VALUES (?, ?)")) {
                ins.setString(1, adminUser);
                ins.setString(2, PasswordHasher.hash(adminPassword));
                ins.executeUpdate();
            }
        }
    }
}
