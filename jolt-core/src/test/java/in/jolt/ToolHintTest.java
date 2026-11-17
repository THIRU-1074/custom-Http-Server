package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolHintTest {

    private static Set<ToolHint> hints(Router r) {
        return r.routes().get(r.routes().size() - 1).hints();
    }

    @Test
    void methodDecidesTheDefaultHint() {
        App app = Jolt.create();
        assertEquals(Set.of(ToolHint.READ_ONLY), hints(app.get("/a", (q, s) -> {}).tool("a")));
        assertEquals(Set.of(ToolHint.DESTRUCTIVE), hints(app.delete("/a", (q, s) -> {}).tool("a")));
        assertEquals(Set.of(ToolHint.IDEMPOTENT), hints(app.put("/a", (q, s) -> {}).tool("a")));
        assertEquals(Set.of(), hints(app.post("/a", (q, s) -> {}).tool("a")));
    }

    @Test
    void explicitBehaviourHintReplacesTheDefault() {
        App app = Jolt.create();
        app.get("/a", (q, s) -> {}).tool("a", ToolHint.DESTRUCTIVE);
        assertEquals(Set.of(ToolHint.DESTRUCTIVE), hints(app));
    }

    @Test
    void safetyHintsKeepTheDefault() {
        App app = Jolt.create();
        app.delete("/a", (q, s) -> {}).tool("a", ToolHint.DRY_RUN, ToolHint.APPROVAL);
        assertEquals(
                Set.of(ToolHint.DESTRUCTIVE, ToolHint.DRY_RUN, ToolHint.APPROVAL), hints(app));
    }

    @Test
    void untaggedRoutesHaveNoHints() {
        App app = Jolt.create();
        app.get("/a", (q, s) -> {});
        assertTrue(hints(app).isEmpty());
    }

    @Test
    void mountedRoutesKeepHintsAndInput() {
        record In(String x) {}
        Router sub = new Router();
        sub.post("/p", In.class, (p, b) -> b).tool("p", ToolHint.APPROVAL);
        App app = Jolt.create();
        app.use("/api", sub);
        Route r = app.routes().get(0);
        assertEquals("/api/p", r.path());
        assertEquals(In.class, r.input());
        assertTrue(r.has(ToolHint.APPROVAL));
    }

    @Test
    void dryRunIsFalseForPlainRequests() throws Exception {
        App app = Jolt.create();
        app.post("/a", (req, res) -> res.text(String.valueOf(req.dryRun())));
        assertEquals("false", JoltTest.of(app).post("/a").send().text());
    }

    @Test
    void routeListingShowsHints() throws Exception {
        App app = Jolt.create().exposeRoutes();
        app.delete("/a", (q, s) -> {}).tool("a");
        assertTrue(
                JoltTest.of(app)
                        .get("/_jolt/routes")
                        .send()
                        .text()
                        .contains("\"hints\":[\"DESTRUCTIVE\"]"));
    }
}
