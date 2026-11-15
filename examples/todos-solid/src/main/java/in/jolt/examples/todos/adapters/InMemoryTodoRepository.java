package in.jolt.examples.todos.adapters;

import in.jolt.examples.todos.domain.Todo;
import in.jolt.examples.todos.domain.TodoNotFound;
import in.jolt.examples.todos.domain.TodoRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

public final class InMemoryTodoRepository implements TodoRepository {

    private final ConcurrentSkipListMap<Long, Todo> rows = new ConcurrentSkipListMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public List<Todo> all() {
        return List.copyOf(rows.values());
    }

    @Override
    public Optional<Todo> find(long id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Todo save(Todo todo) {
        if (todo.id() == 0) {
            Todo stored = new Todo(ids.incrementAndGet(), todo.title(), todo.done());
            rows.put(stored.id(), stored);
            return stored;
        }
        if (rows.replace(todo.id(), todo) == null) {
            throw new TodoNotFound(todo.id());
        }
        return todo;
    }

    @Override
    public Optional<Todo> update(long id, UnaryOperator<Todo> change) {
        return Optional.ofNullable(rows.computeIfPresent(id, (k, t) -> change.apply(t)));
    }

    @Override
    public boolean delete(long id) {
        return rows.remove(id) != null;
    }
}
