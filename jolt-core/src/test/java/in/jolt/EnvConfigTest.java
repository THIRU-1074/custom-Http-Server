package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EnvConfigTest {

    @Test
    void unsetVariablesKeepDefaults() {
        JoltConfig d = JoltConfig.defaults();
        assertEquals(d, JoltConfig.fromEnv(Map.of()).jsonMapper(d.jsonMapper()));
    }

    @Test
    void readsAllThreeVariables() {
        JoltConfig c =
                JoltConfig.fromEnv(
                        Map.of(
                                "JOLT_BIND_ADDRESS", "0.0.0.0",
                                "JOLT_MAX_BODY_BYTES", "2048",
                                "JOLT_MAX_CONNECTIONS", "50"));
        assertEquals("0.0.0.0", c.bindAddress());
        assertEquals(2048, c.maxBodyBytes());
        assertEquals(50, c.maxConnections());
    }

    @ParameterizedTest
    @CsvSource({
        "JOLT_MAX_BODY_BYTES,abc",
        "JOLT_MAX_BODY_BYTES,-1",
        "JOLT_MAX_BODY_BYTES,0",
        "JOLT_MAX_BODY_BYTES,99999999999",
        "JOLT_MAX_CONNECTIONS,1.5",
        "JOLT_MAX_CONNECTIONS,0"
    })
    void invalidValuesFailFastNamingTheVariable(String name, String value) {
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> JoltConfig.fromEnv(Map.of(name, value)));
        assertTrue(e.getMessage().contains(name), e.getMessage());
        assertFalse(e.getMessage().contains("\n"));
    }

    @Test
    void blankValuesCountAsUnset() {
        JoltConfig d = JoltConfig.defaults();
        JoltConfig c =
                JoltConfig.fromEnv(
                        Map.of(
                                "JOLT_BIND_ADDRESS", " ",
                                "BIND_ADDRESS", "",
                                "JOLT_MAX_BODY_BYTES", "  ",
                                "JOLT_MAX_CONNECTIONS", ""));
        assertEquals(d.bindAddress(), c.bindAddress());
        assertEquals(d.maxBodyBytes(), c.maxBodyBytes());
        assertEquals(d.maxConnections(), c.maxConnections());
    }

    @Test
    void deprecatedBindAddressIsAFallbackThatWarnsOnce() {
        List<String> warnings = new ArrayList<>();
        System.Logger log = capture(warnings);
        JoltConfig c = JoltConfig.fromEnv(Map.of("BIND_ADDRESS", "0.0.0.0"), log);
        assertEquals("0.0.0.0", c.bindAddress());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("JOLT_BIND_ADDRESS"), warnings.get(0));
    }

    @Test
    void joltBindAddressWinsAndDoesNotWarn() {
        List<String> warnings = new ArrayList<>();
        JoltConfig c =
                JoltConfig.fromEnv(
                        Map.of("JOLT_BIND_ADDRESS", "10.0.0.1", "BIND_ADDRESS", "0.0.0.0"),
                        capture(warnings));
        assertEquals("10.0.0.1", c.bindAddress());
        assertTrue(warnings.isEmpty());
    }

    private static System.Logger capture(List<String> out) {
        return new System.Logger() {
            public String getName() {
                return "capture";
            }

            public boolean isLoggable(Level level) {
                return true;
            }

            public void log(Level l, java.util.ResourceBundle b, String msg, Throwable t) {
                out.add(l + " " + msg);
            }

            public void log(Level l, java.util.ResourceBundle b, String f, Object... p) {
                out.add(l + " " + f);
            }
        };
    }

    @Test
    void fromEnvReadsTheProcessEnvironment() {
        assertNotNull(JoltConfig.fromEnv());
    }

    @Test
    void closeOnShutdownIsIdempotentAndHookClosesTheServer() throws Exception {
        App app = Jolt.create();
        Server server = app.listen(0);
        int port = server.port();
        assertSame(server, server.closeOnShutdown());
        Thread hook = server.shutdownHook();
        assertNotNull(hook);
        assertSame(server, server.closeOnShutdown());
        assertSame(hook, server.shutdownHook());
        hook.run();
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port));
        hook.run();
        server.close();
    }

    @Test
    void closeUnregistersTheHook() throws Exception {
        Server server = Jolt.create().listen(0);
        server.closeOnShutdown();
        server.close();
        assertNull(server.shutdownHook());
    }
}
