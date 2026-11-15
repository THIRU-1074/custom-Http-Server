package todo;

import jakarta.validation.Valid;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/todos")
public class TodoController {

    private final Map<Integer, Todo> db = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    @GetMapping
    public Collection<Todo> list() {
        return db.values();
    }

    @GetMapping("/{id}")
    public Todo get(@PathVariable int id) {
        return find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Todo create(@Valid @RequestBody TodoInput in) {
        Todo t = new Todo(ids.incrementAndGet(), in.title(), in.done());
        db.put(t.id(), t);
        return t;
    }

    @PutMapping("/{id}")
    public Todo update(@PathVariable int id, @Valid @RequestBody TodoInput in) {
        find(id);
        Todo t = new Todo(id, in.title(), in.done());
        db.put(id, t);
        return t;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int id) {
        db.remove(find(id).id());
    }

    private Todo find(int id) {
        Todo t = db.get(id);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo " + id + " not found");
        }
        return t;
    }
}
