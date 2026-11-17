package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewsTest {

    record User(String name, boolean admin) {}

    private static void file(Path root, String name, String content) throws IOException {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static String render(Path root, String layout, String name, Map<String, ?> model) {
        App app = Jolt.create();
        app.views(root, layout);
        app.get("/", (req, res) -> res.render(name, model));
        return JoltTest.of(app)
                .get("/")
                .expect(200)
                .header("Content-Type", "text/html; charset=utf-8")
                .text();
    }

    private static String render(Path root, String name, Map<String, ?> model) {
        return render(root, null, name, model);
    }

    @Test
    void valuesAreHtmlEscapedByDefaultAndRawWithTriples(@TempDir Path root) throws IOException {
        file(root, "p.html", "<p>{{a}}</p><p>{{{a}}}</p>");
        assertEquals(
                "<p>&lt;b&gt;&amp;&quot;&#39;</p><p><b>&\"'</p>",
                render(root, "p", Map.of("a", "<b>&\"'")));
    }

    @Test
    void missingVariablesRenderEmpty(@TempDir Path root) throws IOException {
        file(root, "p.html", "[{{nope}}][{{a.b.c}}][{{{x}}}]");
        assertEquals("[][][]", render(root, "p", Map.of("a", Map.of())));
    }

    @Test
    void dottedPathsReadMapsAndRecords(@TempDir Path root) throws IOException {
        file(root, "p.html", "{{user.name}}/{{u.name}}/{{u.admin}}/{{n}}");
        assertEquals(
                "Ann/Bob/true/3",
                render(
                        root,
                        "p",
                        Map.of("user", Map.of("name", "Ann"), "u", new User("Bob", true), "n", 3)));
    }

    @Test
    void eachIteratesWithScopeAndDot(@TempDir Path root) throws IOException {
        file(
                root,
                "p.html",
                "{{#each users}}<li>{{name}} of {{team}}</li>{{/each}}|"
                        + "{{#each tags}}{{.}},{{/each}}|{{#each none}}x{{/each}}");
        assertEquals(
                "<li>Ann of red</li><li>Bob of red</li>|a,b,|",
                render(
                        root,
                        "p",
                        Map.of(
                                "team", "red",
                                "users", List.of(Map.of("name", "Ann"), new User("Bob", false)),
                                "tags", List.of("a", "b"),
                                "none", List.of())));
    }

    @Test
    void nullElementsRenderEmpty(@TempDir Path root) throws IOException {
        file(root, "p.html", "{{#each xs}}[{{.}}]{{/each}}|{{#each us}}<{{name}}>{{/each}}");
        assertEquals(
                "[a][][c]|<Ann><><Bob>",
                render(
                        root,
                        "p",
                        Map.of(
                                "xs", Arrays.asList("a", null, "c"),
                                "us",
                                        Arrays.asList(
                                                new User("Ann", true),
                                                null,
                                                new User("Bob", false)))));
    }

    @Test
    void ifTestsTruthiness(@TempDir Path root) throws IOException {
        file(
                root,
                "p.html",
                "{{#if yes}}Y{{/if}}{{#if no}}N{{/if}}{{#if empty}}E{{/if}}"
                        + "{{#if list}}L{{/if}}{{#if missing}}M{{/if}}{{#if blank}}B{{/if}}");
        assertEquals(
                "YL",
                render(
                        root,
                        "p",
                        Map.of(
                                "yes",
                                true,
                                "no",
                                false,
                                "empty",
                                List.of(),
                                "list",
                                List.of(1),
                                "blank",
                                "")));
    }

    @Test
    void partialsAreIncludedWithTheCurrentScope(@TempDir Path root) throws IOException {
        file(root, "partials/nav.html", "<nav>{{title}}</nav>");
        file(root, "p.html", "{{> partials/nav}}|{{#each xs}}{{> partials/nav}}{{/each}}");
        assertEquals(
                "<nav>T</nav>|<nav>T</nav><nav>T</nav>",
                render(root, "p", Map.of("title", "T", "xs", List.of(1, 2))));
    }

    @Test
    void layoutWrapsThePageAndSeesTheModel(@TempDir Path root) throws IOException {
        file(root, "layout.html", "<title>{{title}}</title><main>{{{content}}}</main>");
        file(root, "p.html", "<h1>{{title}}</h1>");
        assertEquals(
                "<title>Hi</title><main><h1>Hi</h1></main>",
                render(root, "layout", "p", Map.of("title", "Hi")));
        assertEquals(
                "<title>Hi</title><main></main>",
                render(root, "layout", "layout", Map.of("title", "Hi")));
    }

    @Test
    void namesAreConfinedToTheRoot(@TempDir Path base) throws IOException {
        Path root = base.resolve("views");
        file(root, "ok.html", "ok");
        file(base, "secret.html", "secret");
        file(root, ".hidden.html", "hidden");
        file(root, "dir/p.html", "{{> ../../secret}}");
        var views = new in.jolt.internal.view.Views(root, null);
        assertEquals("ok", views.render("ok", Map.of()));
        for (String bad :
                new String[] {
                    "../secret",
                    "dir/../../secret",
                    "/secret",
                    "\\secret",
                    "..\\secret",
                    "C:/secret",
                    ".hidden",
                    "ok\0",
                    "",
                    "dir//p",
                    base.resolve("secret.html").toString()
                }) {
            assertThrows(IllegalArgumentException.class, () -> views.render(bad, Map.of()), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> views.render("dir/p", Map.of()));
    }

    @Test
    void missingTemplatesAndBrokenSyntaxFail(@TempDir Path root) throws IOException {
        file(root, "open.html", "{{#each xs}}");
        file(root, "stray.html", "{{/if}}");
        file(root, "mismatch.html", "{{#if a}}{{/each}}");
        file(root, "unterminated.html", "{{a");
        file(root, "loop.html", "{{> loop}}");
        var views = new in.jolt.internal.view.Views(root, null);
        for (String name :
                new String[] {"nope", "open", "stray", "mismatch", "unterminated", "loop"}) {
            assertThrows(IllegalArgumentException.class, () -> views.render(name, Map.of()), name);
        }
    }

    @Test
    void editedTemplatesAreReloadedByMtime(@TempDir Path root) throws IOException {
        Path f = root.resolve("p.html");
        Files.writeString(f, "one");
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_700_000_000_000L));
        var views = new in.jolt.internal.view.Views(root, null);
        assertEquals("one", views.render("p", Map.of()));
        assertEquals("one", views.render("p", Map.of()));
        Files.writeString(f, "two");
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_800_000_000_000L));
        assertEquals("two", views.render("p", Map.of()));
        Files.delete(f);
        assertThrows(IllegalArgumentException.class, () -> views.render("p", Map.of()));
    }

    @Test
    void renderNeedsViewsConfigured() {
        assertThrows(IllegalStateException.class, () -> new Response(null).render("p", Map.of()));
        App app = Jolt.create();
        app.get("/", (req, res) -> res.render("p", Map.of()));
        JoltTest.of(app).get("/").expect(500);
    }
}
