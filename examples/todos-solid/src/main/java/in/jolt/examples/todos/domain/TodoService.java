package in.jolt.examples.todos.domain;

import java.util.List;

/** Todo use cases. Knows the repository interface and nothing about HTTP. */
public class TodoService {

    private final TodoRepository repository;

    public TodoService(TodoRepository repository) {
        this.repository = repository;
    }

    public List<Todo> list() {
        return repository.all();
    }

    public Todo add(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        return repository.save(new Todo(0, title.trim(), false));
    }

    public Todo get(long id) {
        return repository.find(id).orElseThrow(() -> new TodoNotFound(id));
    }

    public Todo setDone(long id, boolean done) {
        Todo t = get(id);
        return repository.save(new Todo(t.id(), t.title(), done));
    }

    public void remove(long id) {
        if (!repository.delete(id)) {
            throw new TodoNotFound(id);
        }
    }
}
