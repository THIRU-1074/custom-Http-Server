package in.jolt.examples.todos.domain;

public class TodoNotFound extends RuntimeException {

    public TodoNotFound(long id) {
        super("Todo " + id + " not found");
    }
}
