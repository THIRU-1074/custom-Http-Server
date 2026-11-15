package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class TypedOverloadTest {

    record In(String name) {}

    record Out(String id) {}

    private Out handle(Params p) {
        return new Out(p.param("id"));
    }

    private static Out viaStatic(Params p) {
        return new Out("s" + p.param("id"));
    }

    private void raw(Request req, Response res) {
        res.text("raw");
    }

    private void run(Params p) {}

    private Reply<Out> created(Params p) {
        return Reply.created(new Out(p.param("id")));
    }

    private Out write(Params p, In in) {
        return new Out(in.name());
    }

    @Test
    void bodilessTypedVariantsAnswerJson() {
        App app = Jolt.create();
        app.post("/p/:id", p -> new Out(p.param("id")));
        app.put("/p/:id", p -> new Out(p.param("id")));
        app.patch("/p/:id", p -> new Out(p.param("id")));
        app.post("/made/:id", this::created);
        app.post("/none", p -> null);
        JoltTest t = JoltTest.of(app);
        assertEquals(new Out("1"), t.post("/p/1").expect(200).json(Out.class));
        assertEquals(new Out("2"), t.put("/p/2").expect(200).json(Out.class));
        assertEquals(new Out("3"), t.patch("/p/3").expect(200).json(Out.class));
        t.post("/made/4").expect(201);
        t.post("/none").expect(204).body("");
    }

    @Test
    void methodReferencesAndExplicitLambdasAreUnambiguous() {
        App app = Jolt.create();
        app.get("/a/:id", this::handle);
        app.post("/a/:id", this::handle);
        app.put("/a/:id", this::handle);
        app.patch("/a/:id", this::handle);
        app.delete("/a/:id", this::run);
        app.get("/b/:id", TypedOverloadTest::viaStatic);
        app.post("/b/:id", TypedOverloadTest::viaStatic);
        app.put("/b/:id", TypedOverloadTest::viaStatic);
        app.patch("/b/:id", TypedOverloadTest::viaStatic);
        app.delete("/b/:id", this::handle);
        app.get("/c/:id", (Params p) -> handle(p));
        app.post("/c/:id", (Params p) -> handle(p));
        app.put("/c/:id", (Params p) -> handle(p));
        app.patch("/c/:id", (Params p) -> handle(p));
        app.delete("/c/:id", (Params p) -> run(p));
        app.get("/d", this::raw);
        app.post("/d", this::raw);
        app.put("/d", this::raw);
        app.patch("/d", this::raw);
        app.delete("/d", this::raw);
        app.post("/w", In.class, this::write);
        app.put("/w", In.class, this::write);
        app.patch("/w", In.class, this::write);
        app.get("/e", (Request req, Response res) -> res.text("raw"));
        app.post("/e", (Request req, Response res) -> res.text("raw"));
        app.delete("/e", (Request req, Response res) -> res.text("raw"));
        JoltTest t = JoltTest.of(app);
        t.get("/a/1").expect(200);
        t.patch("/a/1").expect(200);
        t.delete("/a/1").expect(204);
        t.put("/b/1").expect(200).bodyContains("s1");
        t.delete("/b/1").expect(204);
        t.post("/c/1").expect(200);
        t.delete("/c/1").expect(204);
        t.delete("/d").expect(200).body("raw");
        t.post("/w").json(new In("n")).expect(200).bodyContains("n");
        t.post("/e").expect(200).body("raw");
    }
}
