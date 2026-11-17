package in.jolt.examples.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordStorageTest {

    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;

    @BeforeEach
    void useTempDb() {
        System.setProperty("jolt.db", dir.resolve("test.db").toString());
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty("jolt.db");
    }

    @Test
    void seededAdminCanLogInAndWrongPasswordIsRejected() throws Exception {
        DBConnection.init("admin", PASSWORD);
        assertTrue(AttendanceApp.authenticate("admin", PASSWORD));
        assertFalse(AttendanceApp.authenticate("admin", "wrong password!!"));
        assertFalse(AttendanceApp.authenticate("nobody", PASSWORD));
    }

    @Test
    void databaseNeverContainsThePlaintextPassword() throws Exception {
        DBConnection.init("admin", PASSWORD);
        try (Connection c = DBConnection.getConnection();
                ResultSet rs = c.createStatement().executeQuery("SELECT * FROM users")) {
            assertTrue(rs.next());
            assertFalse(rs.getString("password_hash").contains(PASSWORD));
        }
        String raw =
                new String(Files.readAllBytes(dir.resolve("test.db")), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(PASSWORD));
    }

    @Test
    void withoutAdminConfigNoUserIsSeeded() throws Exception {
        DBConnection.init(null, null);
        try (Connection c = DBConnection.getConnection();
                ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM users")) {
            assertEquals(0, rs.getInt(1));
        }
        assertFalse(AttendanceApp.authenticate("student", "student"));
    }

    @Test
    void shortAdminPasswordIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DBConnection.init("admin", "short"));
    }
}
