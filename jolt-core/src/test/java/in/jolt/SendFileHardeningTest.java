package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.RawClient.Reply;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class SendFileHardeningTest {

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private Server serve(JoltConfig config, Path root) {
        App app = Jolt.create(config);
        app.use(Static.serve("/s", root));
        app.get("/d", (req, res) -> res.sendFile(root));
        app.use("/s", (req, res, next) -> res.status(404).text("fell through"));
        server = app.listen(0);
        return server;
    }

    @Test
    void aFileUsedAsADirectoryFallsThroughInsteadOf500(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.txt"), "ok");
        try (var c = new RawClient(serve(JoltConfig.defaults(), root).port())) {
            Reply r = c.get("/s/a.txt/b");
            assertEquals(404, r.status());
            assertEquals("fell through", r.text());
        }
    }

    @Test
    void sendingADirectoryIs404() throws IOException {
        Path root = Files.createTempDirectory("jolt-dir");
        try (var c = new RawClient(serve(JoltConfig.defaults(), root).port())) {
            assertEquals(404, c.get("/d").status());
        } finally {
            Files.delete(root);
        }
    }

    @Test
    void filesOverTheConfiguredCapAre413(@TempDir Path root) throws IOException {
        Files.write(root.resolve("ten.bin"), new byte[10]);
        Files.write(root.resolve("eleven.bin"), new byte[11]);
        try (var c =
                new RawClient(serve(JoltConfig.defaults().maxStaticFileBytes(10), root).port())) {
            assertEquals(200, c.get("/s/ten.bin").status());
            assertEquals(413, c.get("/s/eleven.bin").status());
        }
    }

    @Test
    void defaultCapIsTenMegabytes() {
        assertEquals(10L * 1024 * 1024, JoltConfig.defaults().maxStaticFileBytes());
    }

    @Test
    void dotfileSegmentsAre404(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".env"), "SECRET=1");
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "x");
        Files.createDirectory(root.resolve("pub"));
        Files.writeString(root.resolve("pub").resolve(".hidden"), "x");
        Files.writeString(root.resolve("pub").resolve("ok.txt"), "ok");
        try (var c = new RawClient(serve(JoltConfig.defaults(), root).port())) {
            assertEquals(404, c.get("/s/.env").status());
            assertEquals(404, c.get("/s/.git/config").status());
            assertEquals(404, c.get("/s/pub/.hidden").status());
            assertEquals("ok", c.get("/s/pub/ok.txt").text());
        }
    }
}
