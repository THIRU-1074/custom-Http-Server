package in.jolt.examples.todos.domain;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Storage port owned by the domain. Adapters implement it. */
public interface TodoRepository {

    /** All todos in id order. */
    List<Todo> all();

    Optional<Todo> find(long id);

    /**
     * Inserts when {@code todo.id()} is 0, otherwise replaces an existing todo.
     *
     * @throws TodoNotFound if the id is not 0 and no such todo exists
     */
    Todo save(Todo todo);

    /** Atomically applies {@code change} to the todo, or returns empty if it does not exist. */
    Optional<Todo> update(long id, UnaryOperator<Todo> change);

    /** Returns whether a todo was removed. */
    boolean delete(long id);
}
