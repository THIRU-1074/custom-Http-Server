# SOLID with Jolt

Jolt has no DI container, no annotations and no classpath scanning. Dependencies are passed through constructors and wired by hand in one place, the composition root. The reference app is [examples/todos-solid](../examples/todos-solid).

```
domain/    Todo, TodoRepository (interface), TodoService, TodoNotFound   no import of in.jolt
adapters/  InMemoryTodoRepository                                       implements the domain interface
web/       TodoModule (RouteModule), NewTodo, Changes                    the only layer that knows HTTP
Main       composition root                                             the only class that names every concrete type
```

## S: single responsibility

Each layer has one reason to change. The service changes with business rules, the module with the HTTP contract, the repository with storage.

- A `RouteModule` registers routes for one feature and holds only its collaborators: `TodoModule`.
- Typed handlers keep each route one line; the work lives in the service: `r.get("/:id", p -> service.get(id(p)))`.
- Mount a module with `app.module("/todos", module)`.

## O: open for extension, closed for modification

Add behaviour by adding code, not by editing existing code.

- New feature: a new `RouteModule` and one `app.module(...)` line in `Main`.
- Cross-cutting concerns (auth, logging, CORS) are `Middleware` registered with `app.use(...)`; handlers never change.
- New error cases: `app.onError(Type.class, mapper)`, for classes or interfaces. The closest registered type wins (superclass and interface steps counted breadth-first, ties to the first registered), so a general mapper can be refined later without touching it. A broad mapper (`RuntimeException`, `Exception`, `Throwable`) never captures an `HttpException`; register one for `HttpException` itself to do that. The mapper registry is thread-safe, so `onError` after `listen()` works, but registering before `listen()` is still the norm.
- Different JSON library: implement `JsonMapper` and pass it via `JoltConfig.jsonMapper`.

## L: Liskov substitution

Any implementation of an interface must be usable where the interface is expected.

- `TodoRepositoryContract` in the example's tests runs the same checks against every `TodoRepository`. A new adapter extends it and inherits the checks.
- `JsonMapperContract` in `jolt-core` tests does the same for `JsonMapper`. Extend it when you write your own mapper.
- `MainTest` runs the unchanged service and module on two different repositories.

## I: interface segregation

Depend on the narrowest type that does the job.

- `Params` exposes only `param(name)` and `query(name)`. Typed handlers receive it instead of `Request`, so they cannot read headers, cookies or the body by accident.
- Keep repository interfaces small. `TodoRepository` has four methods; split a port when a consumer needs only part of it.
- `RouteModule` is a single method, so any lambda or class qualifies.

## D: dependency inversion

High-level policy owns the abstraction; details implement it.

- `TodoService` depends on `TodoRepository`, which lives in `domain`. `InMemoryTodoRepository` depends on the domain, never the reverse.
- `ArchitectureTest` fails the build if `domain` imports `in.jolt`, `adapters` or `web`.
- `Main.app(repository)` is the composition root. Tests call it with a fake; production calls it with the real adapter.
- A module test needs no server: `JoltTest.of(app)` runs requests in memory through the same error path as the socket server.

```java
public static App app(TodoRepository repository) {
    App app = Jolt.create();
    new TodoModule(new TodoService(repository)).install(app);
    return app;
}
```

## Typed handlers at a glance

| Route | Handler shape | Success |
|---|---|---|
| `get(path, p -> result)` | `Typed.Read` | 200 JSON, or 204 for `null` |
| `post/put/patch(path, Body.class, (p, body) -> result)` | `Typed.Write` | 200 JSON, or 204 for `null`; bad body is 400 |
| `delete(path, p -> action())` | `Typed.Run` | 204 |

Return `Reply.created(obj)` or `Reply.status(code, obj)` to choose the status (100-599; 1xx, 204 and 304 take no body, else `IllegalArgumentException`). Throw from anywhere: a registered `onError` mapper decides the reply, `HttpException` keeps its status, anything else is a generic 500 with no details.

## Headers on error replies

Mapped, `HttpException` and generic 500 replies all keep the headers middleware already set (CORS, security headers, `Set-Cookie`), except `Content-Type`, `Content-Length` and `Transfer-Encoding`, which the error body sets. Typed success replies and error replies share one helper, so a `Reply` is written the same way in both.

## What not to do

- **Fat lambdas.** Do not put validation, queries and mapping inside a route lambda. Call a service and return its result.
- **Passing `Request` into services.** It ties business code to HTTP and makes it untestable without a server. Extract values in the module and pass plain arguments.
- **Static singletons.** `static Repo INSTANCE` hides the dependency and blocks substitution. Construct once in `Main` and pass it down.
- **Domain code importing `in.jolt`.** Keep `HttpException` out of services; throw a domain exception and map it with `onError`.
- **Catching exceptions in every handler.** Map them once, centrally.
- **Wiring inside modules.** A module that calls `new JdbcRepository()` cannot be reused or tested with a fake. Take collaborators as constructor arguments.
