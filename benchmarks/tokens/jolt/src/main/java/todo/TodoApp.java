package todo;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TodoApp {

    record Todo(int id, String title, boolean done) {}

    record TodoInput(String title, boolean done) {}

    public static App create() {
        Map<Integer, Todo> db = new ConcurrentHashMap<>();
        AtomicInteger ids = new AtomicInteger();
        App app = Jolt.create();

        app.use(
                "/todos",
                (req, res, next) -> {
                    if (!"Bearer secret".equals(req.header("Authorization"))) {
                        throw new HttpException(401, "Unauthorized");
                    }
                    next.run();
                });

        app.get("/todos", (req, res) -> res.json(db.values()));
        app.get("/todos/:id", (req, res) -> res.json(find(db, req.param("id"))));
        app.post(
                "/todos",
                (req, res) -> {
                    TodoInput in = valid(req.body(TodoInput.class));
                    Todo t = new Todo(ids.incrementAndGet(), in.title(), in.done());
                    db.put(t.id(), t);
                    res.status(201).json(t);
                });
        app.put(
                "/todos/:id",
                (req, res) -> {
                    Todo old = find(db, req.param("id"));
                    TodoInput in = valid(req.body(TodoInput.class));
                    Todo t = new Todo(old.id(), in.title(), in.done());
                    db.put(t.id(), t);
                    res.json(t);
                });
        app.delete(
                "/todos/:id",
                (req, res) -> {
                    db.remove(find(db, req.param("id")).id());
                    res.status(204);
                });
        return app;
    }

    private static Todo find(Map<Integer, Todo> db, String id) {
        Todo t = db.get(Integer.parseInt(id));
        if (t == null) {
            throw new HttpException(404, "Todo " + id + " not found");
        }
        return t;
    }

    private static TodoInput valid(TodoInput in) {
        if (in.title() == null || in.title().isBlank()) {
            throw new HttpException(400, "title is required");
        }
        return in;
    }

    public static void main(String[] args) {
        create().listen(8080);
    }
}
