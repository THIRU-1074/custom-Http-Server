package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AcceptRecoveryTest {

    @Test
    void acceptLoopSurvivesTransientIoErrors() throws IOException {
        AtomicInteger failures = new AtomicInteger(2);
        ServerSocket flaky =
                new ServerSocket() {
                    @Override
                    public Socket accept() throws IOException {
                        if (failures.getAndDecrement() > 0) {
                            throw new IOException("Too many open files");
                        }
                        return super.accept();
                    }
                };
        flaky.bind(new InetSocketAddress(0), 16);
        App app = Jolt.create();
        app.get("/", (req, res) -> res.text("alive"));
        try (Server server = new Server(app.config(), flaky, app.dispatcher());
                RawClient c = new RawClient(server.port())) {
            assertEquals("alive", c.get("/").text());
        }
    }
}
