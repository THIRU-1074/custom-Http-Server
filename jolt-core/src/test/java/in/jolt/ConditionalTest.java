package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConditionalTest {

    private static JoltTest client(Path root) {
        App app = Jolt.create();
        app.get(
                "/e",
                (req, res) ->
                        res.cache(Duration.ofMinutes(5)).json(java.util.Map.of("a", 1)).etag());
        app.post("/e", (req, res) -> res.text("posted").etag());
        app.get("/err", (req, res) -> res.status(404).text("nope").etag());
        app.get("/ns", (req, res) -> res.noStore().text("x"));
        app.use(Static.serve("/s", root));
        return JoltTest.of(app);
    }

    @Test
    void etagIsWeakAndStable(@TempDir Path root) {
        var t = client(root);
        String tag = t.get("/e").expect(200).header("ETag");
        assertNotNull(tag);
        assertEquals(true, tag.startsWith("W/\""));
        assertEquals(tag, t.get("/e").expect(200).header("ETag"));
    }

    @Test
    void ifNoneMatchGives304WithoutBodyAndKeepsValidators(@TempDir Path root) {
        var t = client(root);
        String tag = t.get("/e").expect(200).header("ETag");
        var r = t.get("/e").header("If-None-Match", tag).expect(304);
        assertEquals(0, r.bytes().length);
        assertEquals(tag, r.header("ETag"));
        assertEquals("max-age=300", r.header("Cache-Control"));
    }

    @Test
    void matchingIsWeakListAwareAndSupportsStar(@TempDir Path root) {
        var t = client(root);
        String tag = t.get("/e").expect(200).header("ETag");
        String strong = tag.substring(2);
        t.get("/e").header("If-None-Match", strong).expect(304);
        t.get("/e").header("If-None-Match", "\"zzz\", " + tag).expect(304);
        t.get("/e").header("If-None-Match", "*").expect(304);
        t.get("/e").header("If-None-Match", "\"zzz\"").expect(200);
    }

    @Test
    void onlySafeSuccessfulResponsesAreRevalidated(@TempDir Path root) {
        var t = client(root);
        t.post("/e").header("If-None-Match", "*").expect(200);
        t.get("/err").header("If-None-Match", "*").expect(404);
    }

    @Test
    void cacheControlHelpers(@TempDir Path root) {
        var t = client(root);
        t.get("/ns").expect(200).header("Cache-Control", "no-store");
        assertThrows(
                IllegalArgumentException.class,
                () -> new Response(null).cache(Duration.ofSeconds(-1)));
    }

    @Test
    void staticFilesCarryValidatorsAnd304(@TempDir Path root) throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "one");
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_700_000_000_000L));
        var t = client(root);
        var first = t.get("/s/a.txt").expect(200);
        String tag = first.header("ETag");
        String modified = first.header("Last-Modified");
        assertNotNull(tag);
        assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", modified);
        var r = t.get("/s/a.txt").header("If-None-Match", tag).expect(304);
        assertEquals(0, r.bytes().length);
        assertEquals(tag, r.header("ETag"));
        t.get("/s/a.txt").header("If-Modified-Since", modified).expect(304);
        t.get("/s/a.txt").header("If-Modified-Since", "Tue, 14 Nov 2023 22:13:19 GMT").expect(200);
        t.get("/s/a.txt")
                .header("If-None-Match", "\"other\"")
                .header("If-Modified-Since", modified)
                .expect(200);
        t.get("/s/a.txt").header("If-Modified-Since", "garbage").expect(200);
    }

    @Test
    void staticEtagChangesWhenTheFileChanges(@TempDir Path root) throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "one");
        var t = client(root);
        String before = t.get("/s/a.txt").expect(200).header("ETag");
        Files.writeString(f, "three");
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_800_000_000_000L));
        var after = t.get("/s/a.txt").header("If-None-Match", before).expect(200);
        assertNotEquals(before, after.header("ETag"));
        assertEquals("three", after.text());
    }
}
