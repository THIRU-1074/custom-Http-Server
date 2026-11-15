# 0002: SOLID support without a DI container

## Context

Apps built on Jolt should keep business logic free of HTTP types and swap collaborators easily. The constitution forbids dependencies in core and asks for a small API.

## Decision

Add, to `jolt-core` only: `RouteModule`, `Router.module`, `Params`, `Typed` (`Read`, `Write`, `Run`), typed overloads of `get/post/put/patch/delete`, `Reply`, `ErrorMapper` and `App.onError`. Dependency inversion comes from constructor injection and a hand-written composition root. There is no container, no annotation and no classpath scanning.

## Consequences

- Each addition is a thin layer over `Handler`, `Router` mounting and the existing error path; no new runtime machinery.
- Typed `post/put/patch` take `(Params, body)` so path parameters stay reachable. Bodiless POST actions use a plain `Handler`.
- Error mappers live on `App` and run on the shared `ConnectionHandler.respond` path, so `JoltTest` behaves like the socket server.
- `docs/SOLID.md` and `examples/todos-solid` show the intended style.
