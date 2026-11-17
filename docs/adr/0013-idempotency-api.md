# ADR 0013: Idempotency-Key middleware

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Booking and payment POSTs must be safe to retry after a timeout, or a retry double-books or double-charges (#54).

## Decision
- `Idempotency.keys()` is an opt-in `Middleware` with `.store(IdempotencyStore)`, `.ttl(Duration)`, `.inFlightTimeout(Duration)`, `.scope(Function)`, `.methods(String...)`, `.maxBodyBytes(int)` and `.required()`. It acts on POST, PUT and PATCH carrying `Idempotency-Key` (1 to 128 characters in 0x21-0x7E, else 400); other requests pass untouched, and the unused path costs one set lookup and one header read. `.ttl` and `.inFlightTimeout` configure the default store only; combining them with `.store`, in either order, throws `IllegalStateException`.
- The fingerprint is SHA-256 over method, path, raw query, `Content-Type` and body. Same key and fingerprint replays the stored status, body and every header except `Set-Cookie`, `Date`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Keep-Alive` and `Idempotency-Replayed`, so a stored `Content-Encoding` stays decodable. A bodyless response (204, 304, 3xx) replays bodyless with no invented `Content-Type`. Same key with another fingerprint is 422; a repeat while the first runs is 409 with `Retry-After: 1`. The path in the fingerprint is the decoded path the router sees, because `Request` exposes no raw path; `/a%2Fb` and `/a/b` are the same request to the handler too.
- Keys are scoped by `.scope(fn)`, by default to `req.attr("user")` when it is text or a number, else to a SHA-256 hash of `Authorization`, else to the remote address. Install it after authentication. With no derivable scope the answer is 400, never a shared bucket. Behind a proxy, `trustProxy` decides the address.
- Not stored, with the key released so a retry runs: 5xx, handlers that throw, streams, bodies over `maxBodyBytes` (1 MB), and 401, 403, 407, 408, 425 and 429, which describe the caller or the moment, not the request's own result. Other 4xx, such as 422, are stored.
- `IdempotencyStore` is an SPI because multi-instance deployments need a shared store. `begin(Key(scope, id), fingerprint)` atomically returns `STARTED`, `IN_FLIGHT`, `COMPLETED`, `MISMATCH` or `FULL` (built with the public `Claim` factories), then `complete(key, Stored)` or `release(key)`. The default holds 10000 entries, at most 1000 per scope, with a 24 h TTL, expires lazily, and a running entry older than 5 minutes counts as released. A scope over its cap loses its own oldest completed entry; at the global cap the largest scope does (a sorted set by size, O(log scopes)), so one client flooding keys cannot evict others. Running entries are never evicted; a store full of them answers 503 with `Retry-After`.
- The response is already buffered by `Response`, so storing keeps a reference to the body bytes rather than copying them.

## Consequences
- jolt-core stays dependency-free (`java.security` only). Apps that do not install it pay nothing.
- Core grows by `Idempotency` and `IdempotencyStore` (with the nested `Key`, `Claim`, `State` and `Stored`), at one `llms-extras.txt` line.
- Requests with the same key arriving while another runs are rejected with 409 rather than queued, so clients must retry.
- The in-memory store is per process; a restart or a second instance forgets keys.
