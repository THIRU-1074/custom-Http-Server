package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @TempDir Path dir;

    private void write(String name, String body) throws IOException {
        Files.writeString(dir.resolve(name), body);
    }

    @Test
    void readsPropertiesWhenNoEnv() throws IOException {
        write("application.properties", "db.url=jdbc:h2:mem:x\nhold.minutes=10\n");
        Config c = Config.load(Map.of(), dir);
        assertEquals("jdbc:h2:mem:x", c.string("db.url"));
        assertEquals(10, c.integer("hold.minutes"));
    }

    @Test
    void missingFileIsFine() {
        Config c = Config.load(Map.of("APP_NAME", "x"), dir);
        assertEquals("x", c.string("app.name"));
    }

    @Test
    void envWinsOverFileUsingExactOrUpperSnakeName() throws IOException {
        write("application.properties", "db.url=file\nprice.cap=5\nexact-key=file\n");
        Config c = Config.load(Map.of("DB_URL", "env", "price.cap", "9"), dir);
        assertEquals("env", c.string("db.url"));
        assertEquals(9, c.integer("price.cap"));
        assertEquals("file", c.string("exact-key"));
    }

    @Test
    void profileFileOverlaysBaseAndEnvStillWins() throws IOException {
        write("application.properties", "a=base\nb=base\nc=base\n");
        write("application-prod.properties", "b=prod\nc=prod\n");
        Config c = Config.load(Map.of("JOLT_PROFILE", "prod", "C", "env"), dir);
        assertEquals("base", c.string("a"));
        assertEquals("prod", c.string("b"));
        assertEquals("env", c.string("c"));
        assertEquals("prod", c.profile());
    }

    @Test
    void noProfileMeansNoProfileFile() throws IOException {
        write("application-prod.properties", "b=prod\n");
        Config c = Config.load(Map.of(), dir);
        assertEquals("fallback", c.string("b", "fallback"));
        assertNull(c.profile());
    }

    @Test
    void unsafeProfileNamesAreRejected() {
        assertThrows(ConfigException.class, () -> Config.load(Map.of("JOLT_PROFILE", "../x"), dir));
    }

    @Test
    void stringForms() {
        Config c = Config.load(Map.of("A", "x", "BLANK", "  "), dir);
        assertEquals("x", c.string("a"));
        assertEquals("d", c.string("nope", "d"));
        assertEquals("d", c.string("blank", "d"));
        ConfigException e = assertThrows(ConfigException.class, () -> c.string("db.url"));
        assertTrue(e.getMessage().contains("db.url"), e.getMessage());
        assertFalse(e.getMessage().contains("\n"));
        assertThrows(ConfigException.class, () -> c.string("blank"));
    }

    @Test
    void integerForms() {
        Config c = Config.load(Map.of("N", " 42 ", "BAD", "4x", "BIG", "99999999999"), dir);
        assertEquals(42, c.integer("n"));
        assertEquals(7, c.integer("nope", 7));
        ConfigException e = assertThrows(ConfigException.class, () -> c.integer("bad"));
        assertTrue(e.getMessage().contains("bad"), e.getMessage());
        assertThrows(ConfigException.class, () -> c.integer("big"));
        assertThrows(ConfigException.class, () -> c.integer("bad", 1));
        assertThrows(ConfigException.class, () -> c.integer("nope"));
    }

    @Test
    void durationForms() {
        Config c =
                Config.load(
                        Map.of(
                                "A", "30s", "B", "5m", "C", "2h", "D", "500ms", "E", "1d", "F",
                                "PT1M30S", "G", "10", "BAD", "soon"),
                        dir);
        assertEquals(Duration.ofSeconds(30), c.duration("a"));
        assertEquals(Duration.ofMinutes(5), c.duration("b"));
        assertEquals(Duration.ofHours(2), c.duration("c"));
        assertEquals(Duration.ofMillis(500), c.duration("d"));
        assertEquals(Duration.ofDays(1), c.duration("e"));
        assertEquals(Duration.ofSeconds(90), c.duration("f"));
        assertEquals(Duration.ofSeconds(10), c.duration("nope", Duration.ofSeconds(10)));
        assertThrows(ConfigException.class, () -> c.duration("g"));
        ConfigException e = assertThrows(ConfigException.class, () -> c.duration("bad"));
        assertTrue(e.getMessage().contains("bad"), e.getMessage());
        assertThrows(ConfigException.class, () -> c.duration("nope"));
    }

    @Test
    void boolForms() {
        Config c = Config.load(Map.of("T", "TRUE", "F", "false", "BAD", "yes"), dir);
        assertTrue(c.bool("t"));
        assertFalse(c.bool("f"));
        assertTrue(c.bool("nope", true));
        assertThrows(ConfigException.class, () -> c.bool("bad"));
        assertThrows(ConfigException.class, () -> c.bool("nope"));
    }

    @Test
    void errorMessagesNeverEchoTheValue() {
        Config c = Config.load(Map.of("JWT_SECRET", "hunter2hunter2"), dir);
        ConfigException e = assertThrows(ConfigException.class, () -> c.integer("jwt.secret"));
        assertTrue(e.getMessage().contains("jwt.secret"), e.getMessage());
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
    }

    @Test
    void toStringMasksSecretsPasswordsAndTokens() throws IOException {
        write(
                "application.properties",
                "db.url=jdbc:x\nJwt.Secret=s3cr3t\ndb.password=pw\napi.token=tok\nhold=5\n");
        String s = Config.load(Map.of("DB_PASSWORD", "envpw"), dir).toString();
        assertTrue(s.contains("db.url=jdbc:x"), s);
        assertTrue(s.contains("hold=5"), s);
        assertFalse(s.contains("s3cr3t") || s.contains("envpw") || s.contains("tok="), s);
        assertTrue(s.contains("Jwt.Secret=****"), s);
        assertTrue(s.contains("db.password=****"), s);
        assertTrue(s.contains("api.token=****"), s);
    }

    @Test
    void toStringShowsTheEffectiveValueAfterEnvOverride() throws IOException {
        write("application.properties", "hold=5\n");
        assertTrue(Config.load(Map.of("HOLD", "8"), dir).toString().contains("hold=8"));
    }

    @Test
    void loadReadsTheProcessEnvironment() {
        Config c = Config.load();
        assertEquals(System.getenv("PATH") != null, c.has("path"));
    }
}
