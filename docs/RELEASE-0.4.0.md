# Jolt 0.4.0 release notes (draft)

Release candidate branch `release/0.4.0-rc`. Versions are not bumped and nothing is tagged. ADRs 0014 to 0021 were renumbered so every number is unique.

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

## #68 Static files and templates from the class path
`Static.classpath(root)`, `Static.classpath(urlPrefix, root[, ClassLoader])`, `app.viewsClasspath(root, layout[, ClassLoader])`. ADR 0016.

## #69 JoltTest streaming and SSE
`Result.events()`, `Result.bytes()`/`text()` for streamed bodies, `Call.maxEvents(n)`, `Call.timeout(Duration)`, `Result.streamError()`. ADR 0020.

## #71 App-defined metrics and health checks
`app.metrics()` returns `AppMetrics` (`counter`, `gauge`); `app.health(name, check[, timeout])`; `/_jolt/health` answers 200 or 503 with per-check status. ADR 0021.
Breaking: `App` no longer implements `ConnectionHandler.Dispatcher`; `App.metrics()` changed return type. Use `App.dispatcher()` for the server-facing view.

## Not included

| Issue | Status | Why |
| --- | --- | --- |
| #62 Pub/sub hub for SSE and WebSocket | Not included | `feat/sse-hub` has no commits. Its worktree holds only uncommitted edits, and no hub type exists. |
| #70 SSE event ids and Last-Event-ID resume | Not included | Only uncommitted in the `feat/sse-hub` worktree: `EventStream.event(id, name, data)`, `Request.lastEventId()`, a `Live` parameter on `ConnectionHandler.respond`, `SseResumeTest`, and a draft `0017-sse-hub.md` (its decision list covers #70 only). Not committed, so not merged. When it lands, its ADR needs number 0022 because 0017 is now the webhook ADR, and its `Live` plumbing in `ConnectionHandler` must be reconciled with the `StreamCapture` approach from #69. |
