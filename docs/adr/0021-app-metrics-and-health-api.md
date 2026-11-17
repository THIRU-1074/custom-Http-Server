# ADR 0021: App-defined metrics and health checks

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. `exposeMetrics()` reported only Jolt's own series and `/_jolt/health` always answered UP, so apps ran a second hand-rolled endpoint for their own counters, gauges and dependency checks (#71).

## Decision
- `app.metrics()` returns an `AppMetrics` registry, never null, usable before or after `exposeMetrics()` and `listen`. `counter(name, help)` returns an `AppMetrics.Counter` (`increment`, `add(n)` with `n >= 0`, `value`, backed by `LongAdder`); `gauge(name, help, LongSupplier)` is read on every scrape. Both take an optional `Map<String, String>` of fixed labels, so one name can carry several label sets under one `HELP` and `TYPE`. The series render after Jolt's own in `/_jolt/metrics`.
- Names must match `[a-zA-Z_:][a-zA-Z0-9_:]*` and must not start with `jolt_`, which stays reserved for the framework; label names match `[a-zA-Z_][a-zA-Z0-9_]*` and may not start with `__`. Label values are escaped. Violations throw `IllegalArgumentException`; the same name and labels twice, or a counter and a gauge under one name, throw `IllegalStateException` naming the metric. A gauge supplier that throws is logged and left out of that scrape, with no detail in the response.
- `app.health(name, Supplier<Boolean>)` and `health(name, check, Duration)` (default 2 s) register checks. `/_jolt/health` runs them in parallel on virtual threads on each request; a check is DOWN when it returns false or null, throws, or exceeds its timeout (the thread is interrupted). It answers 200 `{"status":"UP","checks":{"db":"UP"}}` when all pass and 503 with the failing checks marked `DOWN` otherwise. Exceptions go to the log at WARNING, never into the body. Names match `[A-Za-z][A-Za-z0-9_.:-]{0,63}` and a duplicate throws `IllegalStateException`. With no checks the body stays `{"status":"UP"}`.
- The health route stays unguarded, as in ADR 0010, so check names (but no details) are visible to anyone who can reach it; use opaque names where that matters.
- `App` no longer implements `ConnectionHandler.Dispatcher`: its `metrics()` method now returns the public registry, so the server's view of the app is a private adapter returned by `App.dispatcher()` (marked not API). `dispatch` and `mapError` stay public. `Server` and `ConnectionHandler` are unchanged.

## Consequences
- Public API grows by `AppMetrics` (with `Counter`), `App.metrics()` (its return type changes from the internal `Metrics`), two `App.health` overloads and `App.dispatcher()`, at one `llms-extras.txt` entry. jolt-core stays dependency-free.
- Apps that register nothing pay one empty registry and one empty check list; the health route returns a constant body and the scrape appends an empty string.
- Code that passed an `App` where a `ConnectionHandler.Dispatcher` was expected must pass `app.dispatcher()`; that type is internal.
- Checks run per request, so a slow dependency is probed as often as the orchestrator polls; keep checks cheap or cache inside the check.
