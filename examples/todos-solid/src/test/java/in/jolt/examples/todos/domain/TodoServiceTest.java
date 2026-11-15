package in.jolt.examples.todos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TodoServiceTest {

    private static final class FakeRepository implements TodoRepository {
        final List<Todo> rows = new ArrayList<>();

        @Override
        public List<Todo> all() {
            return List.copyOf(rows);
        }

        @Override
        public Optional<Todo> find(long id) {
            return rows.stream().filter(t -> t.id() == id).findFirst();
        }

        @Override
        public Todo save(Todo todo) {
            Todo saved =
                    todo.id() == 0 ? new Todo(rows.size() + 1L, todo.title(), todo.done()) : todo;
            rows.removeIf(t -> t.id() == saved.id());
            rows.add(saved);
            return saved;
        }

        @Override
        public boolean delete(long id) {
            return rows.removeIf(t -> t.id() == id);
        }
    }

    private final FakeRepository repo = new FakeRepository();
    private final TodoService service = new TodoService(repo);

    @Test
    void addStoresATrimmedOpenTodo() {
        Todo t = service.add("  buy milk ");
        assertEquals(new Todo(1, "buy milk", false), t);
        assertEquals(List.of(t), service.list());
    }

    @Test
    void addRejectsBlankTitles() {
        assertThrows(IllegalArgumentException.class, () -> service.add("  "));
        assertThrows(IllegalArgumentException.class, () -> service.add(null));
    }

    @Test
    void getUnknownThrowsTodoNotFound() {
        assertThrows(TodoNotFound.class, () -> service.get(9));
    }

    @Test
    void completeMarksDoneAndPersists() {
        long id = service.add("x").id();
        assertTrue(service.setDone(id, true).done());
        assertTrue(repo.find(id).orElseThrow().done());
    }

    @Test
    void removeDeletesOrThrows() {
        long id = service.add("x").id();
        service.remove(id);
        assertTrue(service.list().isEmpty());
        assertThrows(TodoNotFound.class, () -> service.remove(id));
    }
}
