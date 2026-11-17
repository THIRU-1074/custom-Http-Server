# ADR 0018: Long-running tasks

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Agent-era handlers often wait on slow work (an LLM call, a report, a batch import) that outlives a client or proxy timeout. Today an app must hand-roll a job table, ids, polling and cancellation, and usually gets bounds and ownership wrong.

## Decision
- `Tasks` is an opt-in `RouteModule` in `jolt-core`. `Tasks.inMemory()` (64 running, 1000 stored, 15 min TTL) or `Tasks.inMemory(maxRunning, maxStored, ttl)`; `1 <= maxRunning <= maxStored` and a positive TTL, else `IllegalArgumentException`.
- `tasks.start(req, res, Callable<?> work)` runs `work` on a virtual thread and answers `202 Accepted` with `Location: <path>/<id>` and `{id, status: "running", href}`. Ids are 128 random bits from `SecureRandom` (32 hex), so they are unguessable.
- `app.module(tasks)` registers `GET <path>/:id` and `DELETE <path>/:id`. The path defaults to `/tasks` and is set with `.path("/jobs")`. The module knows its own path so `Location` is always right; mounting it under a prefix with `app.module("/x", tasks)` is not supported because `RouteModule.register` does not receive the prefix.
- `GET` returns `{id, status}` with `status` one of `running`, `done` (plus `result`, the work's return value as JSON), `failed` (plus `error: "Task failed"`) or `cancelled`, with `Cache-Control: no-store`. `?wait=s` blocks until the task ends or `s` seconds pass, capped at 30; a non-integer or negative value is 400.
- `DELETE` on a running task marks it `cancelled` and interrupts its thread; on a finished task it forgets it. Both answer 204.
- A throwing job becomes `failed`; the exception is logged with `System.Logger` and never sent to the client.
- `.owner(req -> id)` binds each task to the caller. Other callers get 404 (not 403, so ids are not confirmed). A null owner at start is 401.
- Capacity: when `maxRunning` jobs run, or the store is full of running tasks, `start` answers 503 with `Retry-After: 1`. Finished tasks expire `ttl` after they end; when the store is full the oldest finished task is dropped first. Running tasks are never evicted. Expiry is lazy, on each start and read.

## Alternatives rejected
- A pluggable `TaskStore` SPI: no shared store exists yet and the constitution forbids speculative abstractions. Added later if a multi-instance need appears.
- SSE progress events: `?wait` long-polling covers MCP tools and plain HTTP clients with one route; `res.sse()` is still available to apps that want progress.
- Returning a problem+json body for `failed`: a task's state is a resource, not an error of the GET itself, so GET stays 200.

## Consequences
- `jolt-core` stays dependency-free and grows by one class at one `llms-extras.txt` line. Apps that do not use it pay nothing.
- Tasks live in memory and are lost on restart; a second instance cannot see them.
- The capacity check is per call, not a global lock, so under a burst the stored count can briefly exceed `maxStored` by the number of concurrent starts.
- The module registers `GET` last, so `app.module(tasks).tool("Check a task")` exposes polling as an MCP tool.
