package app;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    record NewTodo(String title) {}

    record Todo(int id, String title) {}

    static App create() {
        var todos = new ConcurrentHashMap<Integer, Todo>();
        var ids = new AtomicInteger();
        var app = Jolt.create();

        app.get("/todos", (req, res) -> res.json(new ArrayList<>(todos.values())));

        app.post("/todos", (req, res) -> {
            var todo = new Todo(ids.incrementAndGet(), req.body(NewTodo.class).title());
            todos.put(todo.id(), todo);
            res.status(201).json(todo);
        });

        app.delete("/todos/:id", (req, res) -> {
            int id = Integer.parseInt(req.param("id"));
            if (todos.remove(id) == null) throw new HttpException(404, "Todo " + id + " not found");
            res.status(204).send(new byte[0]);
        });

        return app;
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        var server = create().listen(port);
        System.out.println("Listening on http://127.0.0.1:" + server.port());
    }
}
