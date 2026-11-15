package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;

class BindAddressTest {

    @Test
    void defaultsToLoopback() {
        assertEquals("127.0.0.1", JoltConfig.defaults().bindAddress());
    }

    @Test
    void fluentCopyKeepsOtherFields() {
        var c = JoltConfig.defaults().maxConnections(7).bindAddress("0.0.0.0");
        assertEquals("0.0.0.0", c.bindAddress());
        assertEquals(7, c.maxConnections());
        assertEquals("0.0.0.0", c.secureCookies(true).bindAddress());
    }

    @Test
    void defaultServerListensOnLoopbackOnly() throws Exception {
        try (var server = Jolt.create().listen(0);
                var s = new Socket(InetAddress.getByName("127.0.0.1"), server.port())) {
            assertTrue(s.isConnected());
        }
    }

    @Test
    void explicitWildcardIsAccepted() throws Exception {
        try (var server = Jolt.create(JoltConfig.defaults().bindAddress("0.0.0.0")).listen(0);
                var s = new Socket(InetAddress.getByName("127.0.0.1"), server.port())) {
            assertTrue(s.isConnected());
        }
    }
}
