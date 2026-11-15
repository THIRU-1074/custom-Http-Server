package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.test.JoltTest;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TypedRoutesTest {

    record In(String name) {}

    record Out(String id, String name) {}

    private JoltTest t;

    @BeforeEach
    void setUp() {
        App app = Jolt.create();
        app.get("/items/:id", p -> new Out(p.param("id"), p.query("q")));
        app.get("/none", p -> null);
        app.get("/boom", p -> Integer.parseInt("x"));
        app.get(
                "/checked",
                p -> {
                    throw new IOException("disk");
                });
        app.get(
                "/denied",
                p -> {
                    throw new HttpException(403, "no");
                });
        app.get("/teapot", p -> Reply.status(418, Map.of("tea", true)));
        app.post("/items", In.class, (p, in) -> Reply.created(new Out("1", in.name())));
        app.put("/items/:id", In.class, (p, in) -> new Out(p.param("id"), in.name()));
        app.patch(
                "/items/:id",
                In.class,
                (p, in) -> Reply.status(202, new Out(p.param("id"), in.name())));
        app.delete("/items/:id", p -> assertEquals("7", p.param("id")));
        app.post("/ack", In.class, (p, in) -> Reply.status(202, null));
        t = JoltTest.of(app);
    }

    @Test
    void getWritesJsonWith200AndExposesParamsAndQuery() {
        t.get("/items/5?q=x").expect(200).json(Out.class);
        assertEquals(new Out("5", "x"), t.get("/items/5?q=x").send().json(Out.class));
    }

    @Test
    void nullResultIs204() {
        t.get("/none").expect(204).body("");
    }

    @Test
    void replyCreatedIs201() {
        t.post("/items").json(new In("a")).expect(201);
        assertEquals(new Out("1", "a"), t.post("/items").json(new In("a")).send().json(Out.class));
    }

    @Test
    void replyStatusSetsAnyStatus() {
        t.get("/teapot").expect(418);
        t.post("/ack").json(new In("a")).expect(202).body("");
    }

    @Test
    void putAndPatchBindBodyAndParams() {
        assertEquals(
                new Out("3", "n"), t.put("/items/3").json(new In("n")).expect(200).json(Out.class));
        t.patch("/items/3").json(new In("n")).expect(202);
    }

    @Test
    void deleteIs204() {
        t.delete("/items/7").expect(204);
    }

    @Test
    void badBodyIs400LikeReqBody() {
        t.post("/items").text("{nope").expect(400);
        t.post("/items").expect(400);
    }

    @Test
    void exceptionsBehaveLikeInHandlers() {
        t.get("/boom").expect(500).body("Internal Server Error");
        t.get("/checked").expect(500).body("Internal Server Error");
        t.get("/denied").expect(403).body("no");
    }
}
