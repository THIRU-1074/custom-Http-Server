package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ToolTagTest {

    @Test
    void toolTagsTheLastRoute() {
        App app = Jolt.create();
        app.get("/a", (req, res) -> res.text("a"));
        app.get("/b", (req, res) -> res.text("b")).tool("Get b");
        assertEquals(new Route("GET", "/a", null), app.routes().get(0));
        assertEquals(new Route("GET", "/b", "Get b"), app.routes().get(1));
    }

    @Test
    void toolBeforeAnyRouteFails() {
        assertThrows(IllegalStateException.class, () -> Jolt.create().tool("x"));
    }
}
