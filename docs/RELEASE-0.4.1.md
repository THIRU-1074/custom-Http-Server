# Jolt 0.4.1 release notes

Three issues: two gaps found while integrating BookMyShow (`from-bms`) and the catalog cache it asked for. ADRs 0024 and 0025.

## #63 In-memory TTL cache with single-flight loading
`TtlCache.create(maxSize, ttl[, clock])`, `get(key, loader)`, `invalidate(key)`, `clear()`, `size()`, `metrics(app.metrics(), name)`.
- LRU eviction, one TTL per cache measured from load completion, no background thread.
- Concurrent misses for one key run the loader once; a failure or null result is propagated to every waiter and not cached.
- Lock-free around loaders, so virtual threads do not pin and a slow key never blocks other keys.
- Metrics: `<name>_hits_total`, `<name>_misses_total`, `<name>_evictions_total`, `<name>_size`. ADR 0025.

## #76 Auth and rate-limit rejections go through the error path
`JwtAuth.middleware()`, `BasicAuth.require(...)`, `Auth.requireRole(...)`, `Auth.requireAll(...)` and `RateLimit` now throw `HttpException` (401, 403, 429) after setting `WWW-Authenticate` / `Retry-After`. With problem+json on they render as problem documents, and `onError(HttpException.class, ...)` sees them. With problem+json off the reply is the same plain text as before. ADR 0024.

## #77 Request-aware error mappers and requestId in problems
`app.onError(Type, (error, req) -> reply)` overload (`ErrorMapper.WithRequest`); `e -> ...` mappers keep working. With problem+json on and `RequestId.middleware()` installed, every problem body includes `requestId` unless the mapper set one. `App.mapError(Throwable, Request)` added; `App.mapError(Throwable)` is deprecated. ADR 0024.

## Behaviour changes
- Code that invokes the auth or rate-limit middleware directly, outside an `App` or `JoltTest`, now sees a thrown `HttpException` instead of a 401/403/429 written to the response.
- An app's `onError(HttpException.class, ...)` mapper now also receives these rejections.

## Not included
- `Idempotency` still writes its 400/409/422 directly (follow-up).
- #36 Maven Central publishing.
