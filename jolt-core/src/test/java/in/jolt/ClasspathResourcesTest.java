package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.internal.http.Resources;
import in.jolt.internal.view.Views;
import in.jolt.test.JoltTest;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathResourcesTest {

    private static final long TIME = 1_700_000_000_000L;
    private static final String PAGE =
            "<title>T</title><nav>T</nav><main><h1>T</h1><nav>T</nav><nav>T</nav></main>";

    private static URLClassLoader jar(Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("web/index.html", "home");
        entries.put("web/app.css", "body{}");
        entries.put("web/logo.svg", "<svg/>");
        entries.put("web/d.txt", "0123456789");
        entries.put("web/docs/index.html", "docs");
        entries.put("web/withdir/", null);
        entries.put("web/withdir/index.html", "withdir");
        entries.put("web/.env", "SECRET");
        entries.put("web/.hidden/index.html", "hidden");
        entries.put("secret.txt", "top");
        entries.put(
                "views/layout.html",
                "<title>{{title}}</title>{{> partials/nav}}<main>{{{content}}}</main>");
        entries.put("views/page.html", "<h1>{{title}}</h1>{{#each xs}}{{> partials/nav}}{{/each}}");
        entries.put("views/partials/nav.html", "<nav>{{title}}</nav>");
        Path jar = dir.resolve("app.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var e : entries.entrySet()) {
                JarEntry entry = new JarEntry(e.getKey());
                entry.setTime(TIME);
                out.putNextEntry(entry);
                if (e.getValue() != null) {
                    out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
    }

    private static JoltTest client(Static mw) {
        App app = Jolt.create();
        app.use("/s", mw);
        app.get("/s/api/ping", (req, res) -> res.json(Map.of("ok", true)));
        return JoltTest.of(app);
    }

    private static void assertServes(JoltTest t) {
        t.get("/s/app.css")
                .expect(200)
                .header("Content-Type", "text/css; charset=utf-8")
                .body("body{}");
        t.get("/s/logo.svg").expect(200).header("Content-Type", "image/svg+xml");
        t.get("/s/index.html").expect(200).header("Content-Type", "text/html; charset=utf-8");
        t.get("/s").expect(301).header("Location", "/s/");
        t.get("/s/").expect(200).body("home");
        t.get("/s/docs").expect(301).header("Location", "/s/docs/");
        t.get("/s/docs/").expect(200).body("docs");
        t.get("/s/api/ping").expect(200).bodyContains("ok");
        t.get("/s/missing.css").expect(404);
        t.head("/s/app.css").expect(200);
        t.post("/s/app.css").expect(404);
    }

    private static void assertValidators(JoltTest t) {
        var first =
                t.get("/s/d.txt").expect(200).header("Accept-Ranges", "bytes").body("0123456789");
        String tag = first.header("ETag");
        String modified = first.header("Last-Modified");
        assertNotNull(tag);
        assertNotNull(modified);
        t.get("/s/d.txt").header("If-None-Match", tag).expect(304).header("ETag", tag);
        t.get("/s/d.txt").header("If-Modified-Since", modified).expect(304);
        t.get("/s/d.txt").header("If-None-Match", "W/\"other\"").expect(200);
        t.get("/s/d.txt")
                .header("Range", "bytes=2-5")
                .expect(206)
                .header("Content-Range", "bytes 2-5/10")
                .body("2345");
        t.get("/s/d.txt").header("Range", "bytes=-3").expect(206).body("789");
        t.get("/s/d.txt")
                .header("Range", "bytes=0-1")
                .header("If-Range", tag)
                .expect(206)
                .body("01");
        t.get("/s/d.txt")
                .header("Range", "bytes=0-1")
                .header("If-Range", "W/\"stale\"")
                .expect(200)
                .body("0123456789");
        t.get("/s/d.txt")
                .header("Range", "bytes=50-")
                .expect(416)
                .header("Content-Range", "bytes */10");
    }

    private static void assertSafe(JoltTest t) {
        t.get("/s/.env").expect(404);
        t.get("/s/.hidden/").expect(404);
        t.get("/s/.hidden/index.html").expect(404);
        t.get("/s/./app.css").expect(404);
        for (String bad :
                new String[] {
                    "/s/../secret.txt",
                    "/s/%2e%2e/secret.txt",
                    "/s/..%2Fsecret.txt",
                    "/s/%2F..%2F..%2Fsecret.txt",
                    "/s/..%5Csecret.txt",
                    "/s/%5C..%5Csecret.txt",
                    "/s/docs/../../secret.txt",
                    "/s/%00"
                }) {
            t.get(bad).header("Accept", "text/html").expect(403);
        }
        t.get("/s//secret.txt").expect(404);
        t.get("/s/C:/secret.txt").expect(404);
    }

    private static void assertSpa(Static mw) {
        var t = client(mw.spaFallback("index.html"));
        t.get("/s/users/42").header("Accept", "text/html").expect(200).body("home");
        t.get("/s/users/42").header("Accept", "application/json").expect(404);
        t.get("/s/missing.js").header("Accept", "text/html").expect(404);
    }

    @Test
    void servesFromExplodedClasses() {
        var mw = Static.classpath("/s", "/jolt-cp-test/static/");
        var t = client(mw);
        assertServes(t);
        assertValidators(t);
        assertSafe(t);
        assertSpa(mw);
    }

    @Test
    void servesFromAPackagedJar(@TempDir Path dir) throws IOException {
        try (var loader = jar(dir)) {
            var mw = Static.classpath("/s", "web", loader);
            var t = client(mw);
            assertServes(t);
            assertValidators(t);
            assertSafe(t);
            assertSpa(mw);
            t.get("/s/withdir").expect(301).header("Location", "/s/withdir/");
            t.get("/s/withdir/").expect(200).body("withdir");
            var r = t.get("/s/d.txt").expect(200);
            assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", r.header("Last-Modified"));
            assertTrue(r.header("ETag").startsWith("W/\"a-"), r.header("ETag"));
        }
    }

    @Test
    void defaultsToTheContextClassLoaderAtTheRootPrefix() {
        App app = Jolt.create();
        app.use(Static.classpath("/jolt-cp-test/static"));
        var t = JoltTest.of(app);
        t.get("/app.css").expect(200).body("body{}");
        t.get("/").expect(200).body("home");
    }

    @Test
    void rootsMustNameASafeDirectory() {
        for (String bad :
                new String[] {"", "/", "//", "a/../b", "..", ".hidden", "a\\b", "a/./b"}) {
            assertThrows(IllegalArgumentException.class, () -> Static.classpath(bad), bad);
        }
    }

    @Test
    void unsupportedUrlSchemesAreNotFound() {
        ClassLoader odd =
                new ClassLoader(null) {
                    @Override
                    protected URL findResource(String name) {
                        try {
                            return java.net.URI.create("http://127.0.0.1:9/" + name).toURL();
                        } catch (java.net.MalformedURLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };
        assertThrows(HttpException.class, () -> Resources.classpath("web", odd).find("a.css"));
        client(Static.classpath("/s", "web", odd)).get("/s/a.css").expect(404);
    }

    @Test
    void templatesRenderFromExplodedClasses() {
        App app = Jolt.create();
        app.viewsClasspath("/jolt-cp-test/views", "layout");
        app.get("/", (req, res) -> res.render("page", Map.of("title", "T", "xs", List.of(1, 2))));
        JoltTest.of(app).get("/").expect(200).body(PAGE);
    }

    @Test
    void templatesRenderFromAPackagedJar(@TempDir Path dir) throws IOException {
        try (var loader = jar(dir)) {
            App app = Jolt.create();
            app.viewsClasspath("views", "layout", loader);
            app.get(
                    "/",
                    (req, res) -> res.render("page", Map.of("title", "T", "xs", List.of(1, 2))));
            var t = JoltTest.of(app);
            t.get("/").expect(200).body(PAGE);
            t.get("/").expect(200).body(PAGE);
        }
    }

    @Test
    void templateNamesAreConfinedToTheClasspathRoot(@TempDir Path dir) throws IOException {
        try (var loader = jar(dir)) {
            var views = new Views(Resources.classpath("views", loader), null);
            assertEquals("<nav>x</nav>", views.render("partials/nav", Map.of("title", "x")));
            for (String bad :
                    new String[] {
                        "../secret",
                        "../web/index",
                        "/secret",
                        "\\secret",
                        "..\\secret",
                        "C:/secret",
                        ".hidden",
                        "ok\0",
                        "",
                        "dir//p",
                        "missing"
                    }) {
                assertThrows(
                        IllegalArgumentException.class, () -> views.render(bad, Map.of()), bad);
            }
        }
    }
}
