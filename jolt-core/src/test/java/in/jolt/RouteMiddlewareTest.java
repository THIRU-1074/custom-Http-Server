package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.test.JoltTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteMiddlewareTest {

    record In(String name) {}

    private final List<String> log = new ArrayList<>();

    private Middleware step(String name) {
        return (req, res, next) -> {
            log.add(name);
            next.run();
        };
    }

    private void guard(Request req, Response res, Middleware.Next next) throws Exception {
        if (req.header("X-Key") == null) {
            res.status(401).text("no key");
            return;
        }
        next.run();
    }

    private void hello(Request req, Response res) {
        log.add("handler");
        res.text("hi");
    }

    @Test
    void chainRunsInOrderThenHandler() {
        App app = Jolt.create();
        app.use(step("global"));
        app.get("/one", step("a"), this::hello);
        app.post("/two", step("a"), step("b"), this::hello);
        JoltTest t = JoltTest.of(app);
        t.get("/one").expect(200).body("hi");
        assertEquals(List.of("global", "a", "handler"), log);
        log.clear();
        t.post("/two").expect(200);
        assertEquals(List.of("global", "a", "b", "handler"), log);
    }

    @Test
    void middlewareCanShortCircuit() {
        App app = Jolt.create();
        app.get("/x", this::guard, step("after"), this::hello);
        JoltTest t = JoltTest.of(app);
        t.get("/x").expect(401).body("no key");
        assertEquals(List.of(), log);
        t.get("/x").header("X-Key", "k").expect(200);
        assertEquals(List.of("after", "handler"), log);
    }

    @Test
    void everyVerbAcceptsMiddleware() {
        App app = Jolt.create();
        app.put("/m", step("m"), (req, res) -> res.text("put"));
        app.patch("/m", step("m"), (req, res) -> res.text("patch"));
        app.delete("/m", step("m"), (req, res) -> res.text("delete"));
        app.get("/m", this::guard, (req, res) -> res.text("get"));
        app.put("/n", step("a"), step("b"), (req, res) -> res.text("put"));
        app.patch("/n", step("a"), step("b"), (req, res) -> res.text("patch"));
        app.delete("/n", step("a"), step("b"), (req, res) -> res.text("delete"));
        app.get("/n", step("a"), step("b"), (req, res) -> res.text("get"));
        JoltTest t = JoltTest.of(app);
        t.put("/m").expect(200).body("put");
        t.patch("/m").expect(200).body("patch");
        t.delete("/m").expect(200).body("delete");
        t.get("/m").expect(401);
        t.put("/n").expect(200).body("put");
        t.patch("/n").expect(200).body("patch");
        t.delete("/n").expect(200).body("delete");
        t.get("/n").expect(200).body("get");
    }

    @Test
    void worksInsideRoutersAndModules() {
        Router api = new Router();
        api.get("/r", step("router"), this::hello);
        App app = Jolt.create();
        app.use("/api", api);
        app.module("/mod", r -> r.get("/m", step("module"), this::hello));
        JoltTest t = JoltTest.of(app);
        t.get("/api/r").expect(200);
        t.get("/mod/m").expect(200);
        assertEquals(List.of("router", "handler", "module", "handler"), log);
    }

    @Test
    void typedRoutesTakeMiddleware() {
        App app = Jolt.create();
        app.get("/g", this::guard, p -> "g");
        app.post("/w", this::guard, In.class, (p, in) -> in.name());
        app.put("/w", this::guard, In.class, (p, in) -> in.name());
        app.patch("/w", this::guard, In.class, (p, in) -> in.name());
        app.post("/b", this::guard, p -> "b");
        app.put("/b", this::guard, p -> "b");
        app.patch("/b", this::guard, p -> "b");
        app.delete("/d", this::guard, p -> {});
        JoltTest t = JoltTest.of(app);
        t.get("/g").expect(401);
        t.get("/g").header("X-Key", "k").expect(200);
        t.delete("/d").expect(401);
        t.delete("/d").header("X-Key", "k").expect(204);
        for (String m : List.of("POST", "PUT", "PATCH")) {
            t.call(m, "/b").expect(401);
            t.call(m, "/b").header("X-Key", "k").expect(200).json(String.class);
            t.call(m, "/w").json(new In("n")).expect(401);
            t.call(m, "/w").json(new In("n")).header("X-Key", "k").expect(200);
        }
    }

    @Test
    void thenComposesLongerChainsAndKeepsShortCircuit() {
        App app = Jolt.create();
        app.get("/c", step("a").then(step("b")).then(step("c")), this::hello);
        app.get("/s", this::guard, step("a").then(this::guard), this::hello);
        JoltTest t = JoltTest.of(app);
        t.get("/c").expect(200);
        assertEquals(List.of("a", "b", "c", "handler"), log);
        t.get("/s").expect(401);
    }

    @Test
    void toolTagStillFollowsTheRoute() {
        App app = Jolt.create();
        app.get("/t", step("a"), this::hello).tool("desc");
        assertEquals("desc", app.routes().get(0).tool());
    }
}
