package in.jolt.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.JoltConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionHandlerTrustTest {

    private static String serve(TrustedProxies trusted) throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket client =
                        new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                Socket accepted = listener.accept()) {
            var handler =
                    new ConnectionHandler(
                            accepted,
                            JoltConfig.defaults(),
                            (req, res) -> res.text(req.remoteAddress()),
                            () -> false,
                            trusted);
            Thread t = Thread.ofVirtual().start(handler);
            client.getOutputStream()
                    .write(
                            "GET / HTTP/1.1\r\nHost: t\r\nX-Forwarded-For: 4.3.2.1\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
            String reply =
                    new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return reply.substring(reply.lastIndexOf("\r\n\r\n") + 4);
        }
    }

    @Test
    void handlersShareOneCompiledTrustSet() throws IOException {
        TrustedProxies shared = TrustedProxies.of(List.of("127.0.0.1"));
        assertEquals("4.3.2.1", serve(shared));
        assertEquals("4.3.2.1", serve(shared));
        assertEquals("127.0.0.1", serve(TrustedProxies.of(List.of())));
    }
}
