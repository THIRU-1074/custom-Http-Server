package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.test.JoltTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodoAppTest {

    private final JoltTest http = JoltTest.of(Main.create());

    @Test
    void startsEmpty() {
        http.get("/todos").expect(200).body("[]");
    }

    @Test
    void createsAndListsTodos() {
        var created = http.post("/todos").json(Map.of("title", "learn Jolt")).expect(201);
        assertEquals(new Main.Todo(1, "learn Jolt"), created.json(Main.Todo.class));
        http.get("/todos").expect(200).bodyContains("learn Jolt");
    }

    @Test
    void deletesTodo() {
        http.post("/todos").json(Map.of("title", "x")).expect(201);
        http.delete("/todos/1").expect(204);
        http.delete("/todos/1").expect(404);
    }
}
