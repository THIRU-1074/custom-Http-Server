package in.jolt.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.App;
import in.jolt.Jolt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JoltTestSessionTest {

    private App app;

    @BeforeEach
    void setUp() {
        app = Jolt.create();
        app.post(
                "/login",
                (req, res) -> {
                    res.cookie("sid", "abc").cookie("theme", "dark").text("ok");
                });
        app.get("/me", (req, res) -> res.text(String.valueOf(req.cookie("sid"))));
        app.get("/themes", (req, res) -> res.text(String.valueOf(req.cookie("theme"))));
        app.post("/logout", (req, res) -> res.cookie("sid", "", "Path=/; Max-Age=0").text("bye"));
        app.get(
                "/profile",
                (req, res) ->
                        res.json(
                                Map.of(
                                        "name",
                                        "Ada",
                                        "age",
                                        36,
                                        "address",
                                        Map.of("city", "London"),
                                        "tags",
                                        List.of("a", "b"))));
    }

    @Test
    void sessionKeepsCookiesBetweenCalls() {
        var s = JoltTest.of(app).session();
        s.get("/me").expect(200).body("null");
        s.post("/login").expect(200);
        s.get("/me").expect(200).body("abc");
        s.get("/themes").expect(200).body("dark");
    }

    @Test
    void plainClientStaysStateless() {
        var t = JoltTest.of(app);
        t.post("/login").expect(200);
        t.get("/me").expect(200).body("null");
    }

    @Test
    void sessionsAreIndependent() {
        var a = JoltTest.of(app).session();
        var b = JoltTest.of(app).session();
        a.post("/login").expect(200);
        b.get("/me").expect(200).body("null");
    }

    @Test
    void maxAgeZeroDeletesTheCookie() {
        var s = JoltTest.of(app).session();
        s.post("/login").expect(200);
        s.post("/logout").expect(200);
        s.get("/me").expect(200).body("null");
        s.get("/themes").expect(200).body("dark");
    }

    @Test
    void explicitCookieHeaderWins() {
        var s = JoltTest.of(app).session();
        s.post("/login").expect(200);
        s.get("/me").header("Cookie", "sid=manual").expect(200).body("manual");
    }

    @Test
    void jsonFieldReadsNestedPaths() {
        var r = JoltTest.of(app).get("/profile").expect(200);
        assertEquals("Ada", r.jsonField("name"));
        assertEquals("London", r.jsonField("address.city"));
        assertEquals("b", r.jsonField("tags.1"));
        assertNull(r.jsonField("missing"));
    }

    @Test
    void jsonEqualsPassesAndChains() {
        JoltTest.of(app)
                .get("/profile")
                .expect(200)
                .jsonEquals("name", "Ada")
                .jsonEquals("age", 36)
                .jsonEquals("address.city", "London");
    }

    @Test
    void jsonEqualsFailureNamesThePathAndValues() {
        var r = JoltTest.of(app).get("/profile").expect(200);
        var e = assertThrows(AssertionError.class, () -> r.jsonEquals("address.city", "Paris"));
        assertEquals(
                "GET /profile: expected json address.city <Paris> but was <London>",
                e.getMessage());
        var m = assertThrows(AssertionError.class, () -> r.jsonEquals("nope.x", "v"));
        assertEquals("GET /profile: expected json nope.x <v> but was <null>", m.getMessage());
    }

    @Test
    void jsonOnNonJsonBodyFailsClearly() {
        var r = JoltTest.of(app).post("/login").expect(200);
        var e = assertThrows(AssertionError.class, () -> r.jsonField("a"));
        assertEquals("POST /login: body is not valid JSON", e.getMessage());
    }
}
