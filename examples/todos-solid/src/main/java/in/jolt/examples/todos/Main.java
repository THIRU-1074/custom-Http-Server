package in.jolt.examples.todos;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.Server;
import in.jolt.examples.todos.adapters.InMemoryTodoRepository;
import in.jolt.examples.todos.domain.TodoRepository;
import in.jolt.examples.todos.domain.TodoService;
import in.jolt.examples.todos.web.TodoModule;

/** Composition root: the only place that knows every concrete class. */
public final class Main {

    private Main() {}

    /** Wires service and module around {@code repository}. */
    public static App app(TodoRepository repository) {
        App app = Jolt.create();
        new TodoModule(new TodoService(repository)).install(app);
        return app;
    }

    public static void main(String[] args) {
        String port = System.getenv("PORT");
        Server server =
                app(new InMemoryTodoRepository())
                        .listen(port == null ? 8080 : Integer.parseInt(port));
        System.getLogger("todos")
                .log(System.Logger.Level.INFO, "Listening on port " + server.port());
    }
}
