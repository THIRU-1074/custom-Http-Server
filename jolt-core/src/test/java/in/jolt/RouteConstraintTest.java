package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.internal.routing.RouteTrie;
import in.jolt.test.JoltTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteConstraintTest {

    private static App app() {
        App app = Jolt.create();
        app.get("/users/:id(\\d+)", (req, res) -> res.text("num " + req.param("id")));
        app.get("/users/:name", (req, res) -> res.text("name " + req.param("name")));
        app.get("/users/me", (req, res) -> res.text("me"));
        app.get("/files/:name?", (req, res) -> res.text("file " + req.param("name")));
        app.get("/only/:id([a-f]+)", (req, res) -> res.text("hex " + req.param("id")));
        return app;
    }

    @Test
    void constraintSelectsAmongParamRoutes() {
        JoltTest t = JoltTest.of(app());
        t.get("/users/42").expect(200).body("num 42");
        t.get("/users/ada").expect(200).body("name ada");
        t.get("/users/me").expect(200).body("me");
    }

    @Test
    void constraintIsFullMatch() {
        JoltTest t = JoltTest.of(app());
        t.get("/only/abc").expect(200).body("hex abc");
        t.get("/only/abc1").expect(404);
    }

    @Test
    void constraintMissFallsThroughToNotFound() {
        JoltTest.of(app()).get("/only/zzz").expect(404);
    }

    @Test
    void optionalTrailingParam() {
        JoltTest t = JoltTest.of(app());
        t.get("/files").expect(200).body("file null");
        t.get("/files/a.txt").expect(200).body("file a.txt");
    }

    @Test
    void optionalWithConstraint() {
        var trie = new RouteTrie<String>();
        trie.insert("/p/:n(\\d+)?", "p");
        assertEquals("p", trie.find("/p").value());
        assertEquals(Map.of("n", "7"), trie.find("/p/7").params());
        assertNull(trie.find("/p/x"));
    }

    @Test
    void precedenceStaticThenConstrainedThenPlainThenWildcard() {
        var trie = new RouteTrie<String>();
        trie.insert("/a/*", "wild");
        trie.insert("/a/:x", "plain");
        trie.insert("/a/:x(\\d+)", "constrained");
        trie.insert("/a/7", "static");
        assertEquals("static", trie.find("/a/7").value());
        assertEquals("constrained", trie.find("/a/8").value());
        assertEquals("plain", trie.find("/a/z").value());
        assertEquals("wild", trie.find("/a/z/y").value());
    }

    @Test
    void backtrackingLeavesNoStaleParams() {
        var trie = new RouteTrie<String>();
        trie.insert("/a/:x(\\d+)/end", "one");
        trie.insert("/a/:y/other", "two");
        var hit = trie.find("/a/5/other");
        assertEquals("two", hit.value());
        assertEquals(Map.of("y", "5"), hit.params());
    }

    @Test
    void invalidRegexFailsAtRegistration() {
        var trie = new RouteTrie<String>();
        assertThrows(IllegalArgumentException.class, () -> trie.insert("/a/:x([)", "v"));
    }

    @Test
    void duplicateParamNamesFailAtRegistration() {
        var trie = new RouteTrie<String>();
        assertThrows(IllegalArgumentException.class, () -> trie.insert("/a/:x/b/:x", "v"));
        assertThrows(IllegalArgumentException.class, () -> trie.insert("/a/:x(\\d+)/:x", "v"));
    }

    @Test
    void optionalMustBeLast() {
        var trie = new RouteTrie<String>();
        assertThrows(IllegalArgumentException.class, () -> trie.insert("/a/:x?/b", "v"));
    }
}
