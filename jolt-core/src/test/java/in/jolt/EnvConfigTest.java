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
                                "BIND_ADDRESS", "0.0.0.0",
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
        "JOLT_MAX_CONNECTIONS,0",
        "BIND_ADDRESS,' '"
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
