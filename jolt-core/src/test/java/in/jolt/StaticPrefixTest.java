package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class StaticPrefixTest {

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void prefixMatchesOnlyAtASegmentBoundary(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("secret.txt"), "secret");
        App app = Jolt.create();
        app.use(Static.serve("/static", root));
        app.get("/staticsecret.txt", (req, res) -> res.text("route"));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("secret", c.get("/static/secret.txt").text());
            assertEquals("route", c.get("/staticsecret.txt").text());
        }
    }
}
