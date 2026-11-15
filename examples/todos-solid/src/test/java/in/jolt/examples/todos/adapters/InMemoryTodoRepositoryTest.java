package in.jolt.examples.todos.adapters;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.examples.todos.domain.TodoNotFound;
import in.jolt.examples.todos.domain.TodoRepository;
import in.jolt.examples.todos.domain.TodoService;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class InMemoryTodoRepositoryTest extends TodoRepositoryContract {

    @Override
    TodoRepository repository() {
        return new InMemoryTodoRepository();
    }

    @Test
    void concurrentDeleteAndPatchNeverResurrectATodo() throws Exception {
        InMemoryTodoRepository repo = new InMemoryTodoRepository();
        TodoService service = new TodoService(repo);
        for (int i = 0; i < 500; i++) {
            long id = service.add("x").id();
            CountDownLatch go = new CountDownLatch(1);
            Thread patch = new Thread(() -> run(go, () -> service.setDone(id, true)));
            Thread delete = new Thread(() -> run(go, () -> service.remove(id)));
            patch.start();
            delete.start();
            go.countDown();
            patch.join();
            delete.join();
            assertTrue(repo.find(id).isEmpty(), "todo " + id + " came back after delete");
        }
    }

    private static void run(CountDownLatch go, Runnable action) {
        try {
            go.await();
            action.run();
        } catch (TodoNotFound expected) {
            // the other thread won the race
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
