# Jolt 0.4.0 release notes

Fifteen issues raised while building BookMyShow on Jolt (`from-bms`). ADRs 0014 to 0023.

## #52 Multi-valued query parameters (already on main)
`req.queryAll(name)` and `queryAll(name, Class)` on `Params`; `query(name)` stays the first value. ADR 0012.

## #53 App lifecycle hooks (already on main)
`app.onStart`, `app.onStop`, `app.lifecycle(start, stop)`. Start hooks run before the port is bound. ADR 0012.

## #54 Idempotency-Key middleware (already on main)
`Idempotency.keys()` with `IdempotencyStore` SPI, TTL, scope and replay. ADR 0013.

## #55 jolt-db schema migrations
`Migrations.run(Db, Path[, Duration])` and `Migrations.runClasspath(Db, folder[, Duration])` apply `V<n>__<name>.sql` files in order, with checksums, a migration lock and a `jolt_schema_history` table. ADR 0014.

## #56 jolt-db generated keys, batches, immediate transactions, optimistic updates
`Sql.insert`, `Sql.batch`, `Sql.updateExpecting` (throws `OptimisticLockException`), `Db.inTransaction(TxMode, Tx)` with `TxMode.IMMEDIATE`, and `busyTimeout` in place of `busyRetries`. ADR 0014.

## #57 Pagination helper
`Page.of(params)` and `Page.of(params, defaultSize, maxSize)` returning a `(page, size)` record; default size 20, cap 100, bad values are 400. ADR 0015.

## #58 Scheduled and background tasks
`app.every(Duration, Runnable)`, `app.after(Duration, Runnable)`, each with a named overload. Overlapping runs are skipped; `jolt_task_runs_total`, `jolt_task_failures_total` and `jolt_task_skipped_total` appear in `/_jolt/metrics`. ADR 0018.

## #59 Webhook signature verification
`Webhooks.hmacSha256(secret, header)` middleware with `.prefix`, `.timestampHeader`, `.tolerance`, `.clock`. Constant-time compare, uniform 401. ADR 0017.

## #60 Application configuration loader
`Config.load()` reads `application.properties` and the profile file, with environment override. Typed getters `string`, `integer`, `duration`, `bool`; `ConfigException`; secrets masked in `toString()`. ADR 0019.

## #61 Nested and collection validation
`Rules.nested(field, value, validator)` and `Rules.each(field, collection, validator)` with prefixed error keys such as `seats[2].row`. ADR 0015.

## #62 Pub/sub hub for SSE and WebSocket
`Hub.create()` / `Hub.create(queueSize, maxSubscribers, replay)`; `hub.publish(topic, message)` never blocks, `hub.sse(topic, req, res)` serves a topic as SSE with event ids and `Last-Event-ID` replay, `hub.ws(req -> topic)` feeds WebSocket sessions (text only, no ids or replay), `hub.subscribe(topic, lastEventId, sink)` for anything else. Bounded queue per subscriber; one that overflows is dropped with a warning. Over the cap is 503. `hub.metrics(app.metrics(), name)` adds `<name>_subscribers` and `<name>_dropped_total`. ADR 0023.

## #68 Static files and templates from the class path
`Static.classpath(root)`, `Static.classpath(urlPrefix, root[, ClassLoader])`, `app.viewsClasspath(root, layout[, ClassLoader])`. ADR 0016.

## #69 JoltTest streaming and SSE
`Result.events()`, `Result.bytes()`/`text()` for streamed bodies, `Call.maxEvents(n)`, `Call.timeout(Duration)`, `Result.streamError()`. ADR 0020.

## #70 SSE event ids and Last-Event-ID resume
`EventStream.event(id, name, data)` writes an `id:` line (CR, LF or NUL in the id is rejected); `req.lastEventId()` returns the `Last-Event-ID` header. `JoltTest` results already expose ids. ADR 0022.

## #71 App-defined metrics and health checks
`app.metrics()` returns `AppMetrics` (`counter`, `gauge`); `app.health(name, check[, timeout])`; `/_jolt/health` answers 200 or 503 with per-check status. ADR 0021.
Breaking: `App` no longer implements `ConnectionHandler.Dispatcher`; `App.metrics()` changed return type. Use `App.dispatcher()` for the server-facing view.

