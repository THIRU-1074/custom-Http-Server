package in.jolt.examples.todos.domain;

import java.util.List;
import java.util.Optional;

/** Storage port owned by the domain. Adapters implement it. */
public interface TodoRepository {

    /** All todos in id order. */
    List<Todo> all();

    Optional<Todo> find(long id);

    /** Inserts when {@code todo.id()} is 0, otherwise replaces. Returns the stored todo. */
    Todo save(Todo todo);

    /** Returns whether a todo was removed. */
    boolean delete(long id);
}
