package in.jolt.examples.todos.web;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Params;
import in.jolt.Reply;
import in.jolt.RouteModule;
import in.jolt.Router;
import in.jolt.examples.todos.domain.InvalidTodo;
import in.jolt.examples.todos.domain.TodoNotFound;
import in.jolt.examples.todos.domain.TodoService;
import java.util.Map;

/** HTTP face of {@link TodoService}: translates requests to calls and errors to statuses. */
public final class TodoModule implements RouteModule {

    private final TodoService service;

    public TodoModule(TodoService service) {
        this.service = service;
    }

    @Override
    public void register(Router r) {
        r.get("/", p -> service.list());
        r.post("/", NewTodo.class, (p, in) -> Reply.created(service.add(in.title())));
        r.get("/:id", p -> service.get(id(p)));
        r.patch("/:id", Changes.class, (p, in) -> service.setDone(id(p), done(in)));
        r.delete("/:id", p -> service.remove(id(p)));
    }

    /** Mounts the routes under {@code /todos} and maps domain errors to statuses. */
    public void install(App app) {
        app.module("/todos", this);
        app.onError(TodoNotFound.class, e -> Reply.status(404, Map.of("error", e.getMessage())));
        app.onError(InvalidTodo.class, e -> Reply.status(400, Map.of("error", e.getMessage())));
    }

    private static long id(Params p) {
        try {
            return Long.parseLong(p.param("id"));
        } catch (NumberFormatException e) {
            throw new HttpException(400, "Id must be a number");
        }
    }

    private static boolean done(Changes in) {
        if (in.done() == null) {
            throw new HttpException(400, "Field done is required");
        }
        return in.done();
    }
}
