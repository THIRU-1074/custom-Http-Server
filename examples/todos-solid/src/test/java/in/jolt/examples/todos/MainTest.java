package in.jolt.examples.todos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.examples.todos.adapters.InMemoryTodoRepository;
import in.jolt.examples.todos.domain.Todo;
import in.jolt.examples.todos.domain.TodoRepository;
import in.jolt.examples.todos.web.NewTodo;
import in.jolt.test.JoltTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MainTest {

    private static final class SingleSlotRepository implements TodoRepository {
        private Todo slot;

        @Override
        public List<Todo> all() {
            return slot == null ? List.of() : List.of(slot);
        }

        @Override
        public Optional<Todo> find(long id) {
            return Optional.ofNullable(slot).filter(t -> t.id() == id);
        }

        @Override
        public Todo save(Todo todo) {
            slot = todo.id() == 0 ? new Todo(1, todo.title(), todo.done()) : todo;
            return slot;
        }

        @Override
        public boolean delete(long id) {
            boolean had = find(id).isPresent();
            slot = null;
            return had;
        }
    }

    private static void exercise(TodoRepository repo) {
        JoltTest t = JoltTest.of(Main.app(repo));
        t.post("/todos").json(new NewTodo("x")).expect(201);
        assertEquals("x", t.get("/todos/1").expect(200).json(Todo.class).title());
        t.get("/todos/2").expect(404);
    }

    @Test
    void serviceAndModuleRunOnTheInMemoryAdapter() {
        exercise(new InMemoryTodoRepository());
    }

    @Test
    void serviceAndModuleRunOnAnotherAdapterUnchanged() {
        exercise(new SingleSlotRepository());
    }
}
