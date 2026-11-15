package in.jolt.examples.todos.adapters;

import in.jolt.examples.todos.domain.TodoRepository;

class InMemoryTodoRepositoryTest extends TodoRepositoryContract {

    @Override
    TodoRepository repository() {
        return new InMemoryTodoRepository();
    }
}
