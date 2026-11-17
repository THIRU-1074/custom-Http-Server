# ADR 0012: repeated query parameters and app lifecycle hooks

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Two gaps found while building a ticket-booking app: a listing filtered by `?genre=drama&genre=comedy` loses all but the first value (#52), and an app has no place to open and close a DB pool, scheduler or cache with the server (#53).

## Decision
- `Request.queryAll(name)` returns every value in order, or an empty list. `queryAll(name, Class)` converts each value with the same rules as `query(name, Class)` and answers 400 naming the parameter. Both are on `Params`, so typed handlers get them too. `query(name)` and `query()` are unchanged (first value).
- The raw query is parsed once, on first use of `query` or `queryAll`, into one ordered multi-value map; `query(name)` is its first value and `query()` is derived from it, so percent-decoding and `+` handling are identical. A request that touches no query pays nothing. `Params.queryAll` has a default built on `query(name)`, so other implementers keep compiling; `Request` overrides it.
- `App.onStart(Runnable)`, `App.onStop(Runnable)` and the paired `App.lifecycle(start, stop)` are plain registrations, with no hook type, ordering argument or priority. They must precede `listen`; later calls throw `IllegalStateException`, so the list never changes under a running server. A `listen` that fails (port in use, hook failure) leaves registration open again.
- Start hooks run in order before the port is bound, so health checks and load balancers see connection refused, not an open but stalled port, while a migration or warm-up runs. A hook that needs the port can use `Server.port()` only after `listen` returns.
- If a start hook throws, or the bind or server construction fails, `listen` closes anything opened, runs the stop hooks once and rethrows: an `Error` as is, a hook's `RuntimeException` wrapped in `IllegalStateException` naming `onStart`, a bind `IOException` as `UncheckedIOException`. Plain `onStop` hooks always run in that case, so they must tolerate a resource whose start never ran; a `lifecycle` pair stops only if its start completed.
- Stop hooks run at the end of `Server.close()`, after in-flight requests drained and the socket and executor are closed, in reverse registration order. Each runs in its own try/catch and a `Throwable` is logged at WARNING. The first `Error` is rethrown by `close()` after all hooks ran; the JVM shutdown hook only logs it. `close()` stays idempotent, so hooks run once per server.

## Consequences
- No new public types and no dependencies. The hot path is untouched: hooks run only at start and stop, and `queryAll` is opt-in.
- A hook cannot veto or delay shutdown beyond its own run time; a slow stop hook holds `close()`.
- Hooks are per `listen`: an app listened on twice runs them for each server.
