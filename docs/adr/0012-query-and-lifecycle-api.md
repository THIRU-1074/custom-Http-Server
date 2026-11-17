# ADR 0012: repeated query parameters and app lifecycle hooks

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Two gaps found while building a ticket-booking app: a listing filtered by `?genre=drama&genre=comedy` loses all but the first value (#52), and an app has no place to open and close a DB pool, scheduler or cache with the server (#53).

## Decision
- `Request.queryAll(name)` returns every value in order, or an empty list. `queryAll(name, Class)` converts each value with the same rules as `query(name, Class)` and answers 400 naming the parameter. Both are on `Params`, so typed handlers get them too. `query(name)` and `query()` are unchanged (first value).
- The raw query is split and decoded by the same routine as `query()`, so percent-decoding and `+` handling are identical. The multi-value map is built lazily on the first `queryAll` call and cached; requests that never call it pay nothing.
- `App.onStart(Runnable)` and `App.onStop(Runnable)` are plain registrations, with no hook type, ordering argument or priority. They must precede `listen`; later calls throw `IllegalStateException`, so the lists never change under a running server.
- Start hooks run in order after the port is bound and before the acceptor thread starts, so no connection is served early. If one throws, `listen` closes the socket, runs the stop hooks (hooks are not paired, so a stop hook must tolerate a resource whose start never ran) and throws `IllegalStateException` with the cause.
- Stop hooks run at the end of `Server.close()`, after in-flight requests drained, in reverse registration order. Each runs in its own try/catch and a failure is logged at WARNING. `close()` stays idempotent, so hooks run once per server, also via the `closeOnShutdown` hook.

## Consequences
- No new types and no dependencies. The hot path is untouched: hooks run only at start and stop, and `queryAll` is opt-in.
- A hook cannot veto or delay shutdown beyond its own run time; a slow stop hook holds `close()`.
- Hooks are per `listen`: an app listened on twice runs them for each server.
