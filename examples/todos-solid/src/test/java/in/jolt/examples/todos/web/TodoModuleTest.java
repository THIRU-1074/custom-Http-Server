package in.jolt.examples.todos.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.examples.todos.domain.InvalidTodo;
import in.jolt.examples.todos.domain.Todo;
import in.jolt.examples.todos.domain.TodoNotFound;
import in.jolt.examples.todos.domain.TodoService;
import in.jolt.test.JoltTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodoModuleTest {

    private static class FakeService extends TodoService {
        FakeService() {
            super(null);
        }

        @Override
        public List<Todo> list() {
            return List.of(new Todo(1, "a", false));
        }

        @Override
        public Todo add(String title) {
            if (title.isBlank()) {
                throw new InvalidTodo("Title is required");
            }
            return new Todo(2, title, false);
        }

        @Override
        public Todo get(long id) {
            if (id != 1) {
                throw new TodoNotFound(id);
            }
            return new Todo(1, "a", false);
        }

        @Override
        public Todo setDone(long id, boolean done) {
            return new Todo(get(id).id(), "a", done);
        }

        @Override
        public void remove(long id) {
            get(id);
        }
    }

    private final JoltTest t;

    TodoModuleTest() {
        App app = Jolt.create();
        new TodoModule(new FakeService()).install(app);
        t = JoltTest.of(app);
    }

    @Test
    void listReturnsJson() {
        t.get("/todos").expect(200).bodyContains("\"title\":\"a\"");
    }

    @Test
    void postCreatesWith201() {
        Todo made = t.post("/todos").json(new NewTodo("b")).expect(201).json(Todo.class);
        assertEquals(new Todo(2, "b", false), made);
    }

    @Test
    void invalidInputIs400WithErrorBody() {
        t.post("/todos").json(new NewTodo(" ")).expect(400).bodyContains("Title is required");
        t.post("/todos").text("{").expect(400);
        t.get("/todos/abc").expect(400);
    }

    @Test
    void unknownIdIs404ViaTheErrorMapper() {
        t.get("/todos/9").expect(404).bodyContains("Todo 9 not found");
        t.delete("/todos/9").expect(404);
    }

    @Test
    void getPatchAndDelete() {
        t.get("/todos/1").expect(200).bodyContains("\"id\":1");
        assertEquals(
                true,
                t.patch("/todos/1").json(new Changes(true)).expect(200).json(Todo.class).done());
        t.delete("/todos/1").expect(204);
    }

    @Test
    void patchWithoutTheDoneFieldIs400() {
        t.patch("/todos/1").text("{}").expect(400).bodyContains("done");
        t.patch("/todos/1").text("{\"done\":null}").expect(400);
    }

    @Test
    void onlyInvalidTodoIs400NotEveryIllegalArgument() {
        App app = Jolt.create();
        new TodoModule(
                        new FakeService() {
                            @Override
                            public List<Todo> list() {
                                throw new IllegalArgumentException("library bug");
                            }
                        })
                .install(app);
        JoltTest.of(app).get("/todos").expect(500).body("Internal Server Error");
    }
}
