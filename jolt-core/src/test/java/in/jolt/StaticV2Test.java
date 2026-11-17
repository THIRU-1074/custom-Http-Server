package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import in.jolt.test.JoltTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticV2Test {

    private static JoltTest client(Static mw) {
        App app = Jolt.create();
        app.use("/s", mw);
        app.get("/s/api/ping", (req, res) -> res.json(Map.of("ok", true)));
        return JoltTest.of(app);
    }

    private static JoltTest client(Path root) {
        return client(Static.serve("/s", root));
    }

    @Test
    void commonExtensionsGetTheirMediaType(@TempDir Path root) throws IOException {
        Map<String, String> expected =
                Map.ofEntries(
                        Map.entry("a.html", "text/html; charset=utf-8"),
                        Map.entry("a.css", "text/css; charset=utf-8"),
                        Map.entry("a.js", "text/javascript; charset=utf-8"),
                        Map.entry("a.mjs", "text/javascript; charset=utf-8"),
                        Map.entry("a.json", "application/json; charset=utf-8"),
                        Map.entry("a.map", "application/json; charset=utf-8"),
                        Map.entry("a.svg", "image/svg+xml"),
                        Map.entry("a.png", "image/png"),
                        Map.entry("a.jpg", "image/jpeg"),
                        Map.entry("a.gif", "image/gif"),
                        Map.entry("a.webp", "image/webp"),
                        Map.entry("a.ico", "image/x-icon"),
                        Map.entry("a.woff2", "font/woff2"),
                        Map.entry("a.txt", "text/plain; charset=utf-8"),
                        Map.entry("a.pdf", "application/pdf"),
                        Map.entry("a.mp4", "video/mp4"),
                        Map.entry("a.wasm", "application/wasm"),
                        Map.entry("a.UNKNOWN", "application/octet-stream"),
                        Map.entry("noext", "application/octet-stream"));
        var t = client(root);
        for (var e : expected.entrySet()) {
            Files.writeString(root.resolve(e.getKey()), "x");
            t.get("/s/" + e.getKey()).expect(200).header("Content-Type", e.getValue());
        }
    }

    @Test
    void directoriesServeTheirIndexHtml(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("index.html"), "home");
        Files.createDirectory(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("index.html"), "docs");
        Files.createDirectory(root.resolve("bare"));
        var t = client(root);
        t.get("/s").expect(301).header("Location", "/s/");
        t.get("/s/").expect(200).body("home");
        t.get("/s/docs/").expect(200).body("docs");
        t.get("/s/docs").expect(301).header("Location", "/s/docs/");
        t.get("/s/docs?a=1&b=%C3%A9").expect(301).header("Location", "/s/docs/?a=1&b=%C3%A9");
        t.get("/s/bare/").expect(404);
    }

    @Test
    void directoryRedirectsStayOnSiteAndKeepGuarantees(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("index.html"), "docs");
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("index.html"), "git");
        Files.createDirectory(root.resolve("my docs"));
        Files.writeString(root.resolve("my docs").resolve("index.html"), "x");
        App app = Jolt.create();
        app.use(Static.serve(root));
        var t = JoltTest.of(app);
        t.get("//docs").expect(301).header("Location", "/docs/");
        t.get("/my%20docs").expect(301).header("Location", "/my%20docs/");
        t.get("/.git").expect(404);
        t.get("/%2e%2e").expect(403);
    }

    @Test
    void singleRangesGive206(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("d.txt"), "0123456789");
        var t = client(root);
        t.get("/s/d.txt").expect(200).header("Accept-Ranges", "bytes");
        var r = t.get("/s/d.txt").header("Range", "bytes=2-5").expect(206);
        r.header("Content-Range", "bytes 2-5/10").header("Accept-Ranges", "bytes").body("2345");
        t.get("/s/d.txt")
                .header("Range", "bytes=7-")
                .expect(206)
                .header("Content-Range", "bytes 7-9/10")
                .body("789");
        t.get("/s/d.txt")
                .header("Range", "bytes=-3")
                .expect(206)
                .header("Content-Range", "bytes 7-9/10")
                .body("789");
        t.get("/s/d.txt")
                .header("Range", "bytes=8-999")
                .expect(206)
                .header("Content-Range", "bytes 8-9/10")
                .body("89");
        t.get("/s/d.txt").header("Range", "bytes=-99").expect(206).body("0123456789");
    }

    @Test
    void rangeStatusesHaveReasonPhrases() {
        assertEquals("Partial Content", HttpStatus.reasonFor(206));
        assertEquals("Range Not Satisfiable", HttpStatus.reasonFor(416));
        assertEquals("Not Acceptable", HttpStatus.reasonFor(406));
    }

    @Test
    void unsatisfiableRangesGive416(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("d.txt"), "0123456789");
        var t = client(root);
        for (String range : new String[] {"bytes=10-", "bytes=50-60", "bytes=-0"}) {
            var r = t.get("/s/d.txt").header("Range", range).expect(416);
            assertEquals("bytes */10", r.header("Content-Range"));
            assertEquals(0, r.bytes().length);
        }
    }

    @Test
    void multipleMalformedOrConditionalRangesServeTheFullBody(@TempDir Path root)
            throws IOException {
        Files.writeString(root.resolve("d.txt"), "0123456789");
        var t = client(root);
        for (String range : new String[] {"bytes=0-1,4-5", "bytes=abc", "bytes=5-2", "items=0-1"}) {
            var r = t.get("/s/d.txt").header("Range", range).expect(200);
            assertEquals("0123456789", r.text());
            assertNull(r.header("Content-Range"));
        }
        t.get("/s/d.txt")
                .header("Range", "bytes=0-1")
                .header("If-Range", "\"anything\"")
                .expect(200)
                .body("0123456789");
    }

    @Test
    void spaFallbackServesIndexForBrowserNavigations(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("index.html"), "shell");
        Files.writeString(root.resolve("app.js"), "js");
        var t = client(Static.serve("/s", root).spaFallback("index.html"));
        t.get("/s/users/42").header("Accept", "text/html,*/*;q=0.8").expect(200).body("shell");
        t.get("/s/app.js").expect(200).body("js");
        t.get("/s/missing.js").header("Accept", "text/html").expect(404);
        t.get("/s/users/42").header("Accept", "application/json").expect(404);
        t.get("/s/users/42").expect(404);
        t.get("/s/api/ping").header("Accept", "text/html").expect(200).bodyContains("ok");
    }

    @Test
    void handlerThrown404sAreNotReplacedByTheShell(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("index.html"), "shell");
        App app = Jolt.create();
        app.use("/s", Static.serve("/s", root).spaFallback("index.html"));
        app.get(
                "/s/gone",
                (req, res) -> {
                    throw new HttpException(404, "Todo 7 not found");
                });
        var t = JoltTest.of(app);
        t.get("/s/gone").header("Accept", "text/html").expect(404).body("Todo 7 not found");
        t.get("/s/other").header("Accept", "text/html").expect(200).body("shell");
    }

    @Test
    void spaFallbackIsOffByDefaultAndNeedsTheFile(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("index.html"), "shell");
        client(root).get("/s/users/42").header("Accept", "text/html").expect(404);
        client(Static.serve("/s", root).spaFallback("nope.html"))
                .get("/s/users/42")
                .header("Accept", "text/html")
                .expect(404);
    }

    @Test
    void securityGuaranteesHoldWithV2Features(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("index.html"), "shell");
        Files.writeString(root.resolve(".env"), "SECRET");
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("index.html"), "git");
        var t = client(Static.serve("/s", root).spaFallback("index.html"));
        t.get("/s/%2e%2e/%2e%2e/etc/passwd").header("Accept", "text/html").expect(403);
        t.get("/s/.env").header("Accept", "text/html").expect(404);
        t.get("/s/.git/").expect(404);
        t.get("/s/%00").expect(403);
    }
}
