package in.jolt.examples.todos.domain;

/** A todo that breaks a business rule, such as a blank title. */
public class InvalidTodo extends RuntimeException {

    public InvalidTodo(String message) {
        super(message);
    }
}
