package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CorsVaryTest {

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private String varyWhenUpstreamSets(String upstream) throws IOException {
        App app = Jolt.create();
        if (upstream != null) {
            app.use(
                    (req, res, next) -> {
                        res.header("Vary", upstream);
                        next.run();
                    });
        }
        app.use(Cors.allow("https://a.io"));
        app.get("/", (req, res) -> res.text("ok"));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            return c.get("/").header("Vary");
        }
    }

    @Test
    void varyIsOriginWhenNothingSetItBefore() throws IOException {
        assertEquals("Origin", varyWhenUpstreamSets(null));
    }

    @Test
    void appendsToAnExistingVary() throws IOException {
        assertEquals("Accept-Encoding, Origin", varyWhenUpstreamSets("Accept-Encoding"));
    }

    @Test
    void doesNotDuplicateOriginIgnoringCase() throws IOException {
        assertEquals("Accept-Encoding, origin", varyWhenUpstreamSets("Accept-Encoding, origin"));
    }

    @Test
    void wildcardVaryIsLeftAlone() throws IOException {
        assertEquals("*", varyWhenUpstreamSets("*"));
    }
}
