package in.jolt.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.Jolt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JoltTestTest {

    record Todo(String title) {}

    private JoltTest t;

    @BeforeEach
    void setUp() {
        App app = Jolt.create();
        app.use(
                (req, res, next) -> {
                    res.header("X-Seen", "yes");
                    next.run();
                });
        app.get("/hello/:name", (req, res) -> res.text("hi " + req.param("name")));
        app.get("/q", (req, res) -> res.text(req.query("a") + "|" + req.header("X-Token")));
        app.post("/todos", (req, res) -> res.status(201).json(req.body(Todo.class)));
        app.put("/raw", (req, res) -> res.send(req.body()));
        app.delete("/v", (req, res) -> res.text(req.method()));
        app.patch("/v", (req, res) -> res.text(req.method()));
        app.head("/v", (req, res) -> res.text(req.method()));
        app.options("/v", (req, res) -> res.text(req.method()));
        app.get("/teapot", (req, res) -> res.status(418).text("short"));
        app.get(
                "/fail",
                (req, res) -> {
                    throw new HttpException(409, "nope");
                });
        app.get("/boom", (req, res) -> res.text(null));
        t = JoltTest.of(app);
    }

    @Test
    void runsMiddlewareAndRoutesWithPathParams() {
        t.get("/hello/Ada").expect(200).body("hi Ada").header("x-seen", "yes");
    }

    @Test
    void decodesPathAndPassesQueryAndHeaders() {
        t.get("/hello/caf%C3%A9").expect(200).body("hi café");
        t.get("/q?a=x%20y").header("X-Token", "k").expect(200).body("x y|k");
    }

    @Test
    void jsonBodiesRoundTrip() {
        var r = t.post("/todos").json(new Todo("milk")).expect(201);
        assertEquals(new Todo("milk"), r.json(Todo.class));
        r.header("Content-Type", "application/json; charset=utf-8").bodyContains("milk");
    }

    @Test
    void rawAndTextBodiesAreSent() {
        var r = t.put("/raw").body(new byte[] {0, (byte) 0xFF}).expect(200);
        assertArrayEquals(new byte[] {0, (byte) 0xFF}, r.bytes());
        t.put("/raw").text("plain").expect(200).body("plain");
    }

    @Test
    void errorsMapLikeTheServer() {
        t.get("/nowhere").expect(404).bodyContains("Cannot GET /nowhere");
        t.get("/fail").expect(409).body("nope");
        t.post("/todos").text("{").expect(400);
        t.get("/teapot").expect(418).body("short");
        t.get("/nowhere").expect(HttpStatus.NOT_FOUND);
    }

    @Test
    void unhandledExceptionsBecome500() {
        t.get("/boom").expect(500).body("Internal Server Error");
    }

    @Test
    void failedAssertionsThrowAssertionErrorWithContext() {
        var e = assertThrows(AssertionError.class, () -> t.get("/hello/x").expect(201));
        assertEquals("GET /hello/x: expected status <201> but was <200>", e.getMessage());
        assertThrows(AssertionError.class, () -> t.get("/hello/x").expect(200).body("no"));
        assertThrows(AssertionError.class, () -> t.get("/hello/x").expect(200).header("A", "b"));
        assertThrows(AssertionError.class, () -> t.get("/hello/x").expect(200).bodyContains("zz"));
    }

    @Test
    void missingHeaderIsNullAndPathMustBeAbsolute() {
        assertNull(t.get("/hello/x").send().header("Nope"));
        assertThrows(IllegalArgumentException.class, () -> t.get("x").send());
    }

    @Test
    void allVerbsAreAvailable() {
        t.delete("/v").expect(200).body("DELETE");
        t.patch("/v").expect(200).body("PATCH");
        t.head("/v").expect(200);
        t.options("/v").expect(200).body("OPTIONS");
        t.call("PATCH", "/v").expect(HttpStatus.OK);
    }
}
