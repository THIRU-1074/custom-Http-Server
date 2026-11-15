package in.jolt.examples.todos.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.examples.todos.domain.Todo;
import in.jolt.examples.todos.domain.TodoRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour every {@link TodoRepository} must have. */
abstract class TodoRepositoryContract {

    abstract TodoRepository repository();

    @Test
    void saveAssignsIdsAndFindReturnsThem() {
        TodoRepository r = repository();
        Todo a = r.save(new Todo(0, "a", false));
        Todo b = r.save(new Todo(0, "b", false));
        assertTrue(a.id() > 0 && b.id() > a.id());
        assertEquals(a, r.find(a.id()).orElseThrow());
        assertTrue(r.find(999).isEmpty());
    }

    @Test
    void saveWithExistingIdReplaces() {
        TodoRepository r = repository();
        Todo a = r.save(new Todo(0, "a", false));
        r.save(new Todo(a.id(), "a", true));
        assertTrue(r.find(a.id()).orElseThrow().done());
        assertEquals(1, r.all().size());
    }

    @Test
    void allListsInIdOrder() {
        TodoRepository r = repository();
        Todo a = r.save(new Todo(0, "a", false));
        Todo b = r.save(new Todo(0, "b", false));
        assertEquals(List.of(a, b), r.all());
    }

    @Test
    void deleteReportsWhetherARowWasRemoved() {
        TodoRepository r = repository();
        Todo a = r.save(new Todo(0, "a", false));
        assertTrue(r.delete(a.id()));
        assertFalse(r.delete(a.id()));
    }
}
