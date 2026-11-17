# ADR 0024: One error path for stock rejections, request-aware error mappers

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. With `problemJson(true)`, `JwtAuth.middleware()`, `BasicAuth.require(...)`, `Auth.requireRole(...)`/`requireAll(...)` and `RateLimit` wrote plain-text 401/403/429 bodies themselves, so neither problem+json nor `app.onError` saw them (#76). `ErrorMapper` received only the exception, so a mapper could not add the request id or path to a problem body (#77).

## Decision
- The stock rejections set their header (`WWW-Authenticate`, `Retry-After`) on the response, then throw `HttpException` with the same status and message as before. The error path already keeps headers middleware set, so the header survives a mapped reply and a problem document alike. With problem+json off the reply is byte-for-byte the old plain text. No new exception types: a mapper registered for `HttpException` sees them and can branch on `status()`.
- `ErrorMapper.WithRequest<E>` (`Reply<?> map(E error, Request req)`) and the overload `App.onError(Class<E>, ErrorMapper.WithRequest<E>)`. Implicitly typed lambdas pick the overload by arity, so `e -> ...` and `(e, req) -> ...` both compile; existing single-argument mappers are unchanged. Both kinds share one registry and one lookup order.
- `App.mapError(Throwable, Request)` is added; `App.mapError(Throwable)` is deprecated, because it can only pass a `null` request to request-aware mappers.
- When problem+json is on and the request has an id (`RequestId.middleware()` installed), every problem document gets `requestId` after the other members, unless the mapped body already sets it. Plain-text and non-problem JSON errors are unchanged.

## Consequences
- Public API grows by one nested interface, one `onError` overload and one `mapError` overload; one `llms.txt` phrase covers them.
- An app with an `onError(HttpException.class, ...)` mapper now also maps auth and rate-limit rejections. That is the point of #76, but such a mapper should keep the status it is given.
- Code that called these middlewares directly and read `res.statusCode()` now gets an `HttpException` instead. Through an `App` or `JoltTest` nothing changes when problem+json is off.
- `Idempotency` still writes its 400/409/422 directly; it can move to the same path later.
- Errors the parser rejects before routing (413, 431, bad request line) have no request, so they carry no `requestId`.
