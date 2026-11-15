package todo;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TodoApp {

    record Todo(int id, String title, boolean done) {}

    record TodoInput(String title, boolean done) {}

    public static Javalin create() {
        Map<Integer, Todo> db = new ConcurrentHashMap<>();
        AtomicInteger ids = new AtomicInteger();
        Javalin app = Javalin.create();

        app.before(
                "/todos/*",
                ctx -> {
                    if (!"Bearer secret".equals(ctx.header("Authorization"))) {
                        throw new UnauthorizedResponse();
                    }
                });
        app.before(
                "/todos",
                ctx -> {
                    if (!"Bearer secret".equals(ctx.header("Authorization"))) {
                        throw new UnauthorizedResponse();
                    }
                });

        app.get("/todos", ctx -> ctx.json(db.values()));
        app.get("/todos/{id}", ctx -> ctx.json(find(db, ctx.pathParam("id"))));
        app.post(
                "/todos",
                ctx -> {
                    TodoInput in = valid(ctx.bodyAsClass(TodoInput.class));
                    Todo t = new Todo(ids.incrementAndGet(), in.title(), in.done());
                    db.put(t.id(), t);
                    ctx.status(201).json(t);
                });
        app.put(
                "/todos/{id}",
                ctx -> {
                    Todo old = find(db, ctx.pathParam("id"));
                    TodoInput in = valid(ctx.bodyAsClass(TodoInput.class));
                    Todo t = new Todo(old.id(), in.title(), in.done());
                    db.put(t.id(), t);
                    ctx.json(t);
                });
        app.delete(
                "/todos/{id}",
                ctx -> {
                    db.remove(find(db, ctx.pathParam("id")).id());
                    ctx.status(204);
                });
        return app;
    }

    private static Todo find(Map<Integer, Todo> db, String id) {
        Todo t = db.get(Integer.parseInt(id));
        if (t == null) {
            throw new NotFoundResponse("Todo " + id + " not found");
        }
        return t;
    }

    private static TodoInput valid(TodoInput in) {
        if (in.title() == null || in.title().isBlank()) {
            throw new BadRequestResponse("title is required");
        }
        return in;
    }

    public static void main(String[] args) {
        create().start(8080);
    }
}
