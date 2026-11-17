# ADR 0013: Idempotency-Key middleware

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Booking and payment POSTs must be safe to retry after a timeout, or a retry double-books or double-charges (#54).

## Decision
- `Idempotency.keys()` is an opt-in `Middleware` with `.store(IdempotencyStore)`, `.ttl(Duration)`, `.methods(String...)`, `.maxBodyBytes(int)` and `.required()`. It acts on POST, PUT and PATCH carrying `Idempotency-Key` (1 to 128 characters in 0x21-0x7E, else 400); other requests pass untouched, and the unused path costs one set lookup and one header read.
- The fingerprint is SHA-256 over method, path, raw query and body. Same key and fingerprint replays the stored status, `Content-Type`, `Content-Language`, `Location`, `ETag` and body with `Idempotency-Replayed: true`; same key with another fingerprint is 422; a repeat while the first runs is 409 with `Retry-After: 1`. `Set-Cookie` and other headers are never replayed.
- Keys are scoped to `req.attr("user")` when present, else the remote address, so one client cannot replay or probe another's key. Behind a proxy, `trustProxy` decides the address.
- 5xx responses, handlers that throw, streams and bodies over `maxBodyBytes` (1 MB) are not stored; the key is released so a retry runs. 4xx responses are stored, as the request was handled.
- `IdempotencyStore` is an SPI because multi-instance deployments need a shared store. The contract is `begin(key, fingerprint)` returning `STARTED`, `IN_FLIGHT`, `COMPLETED` or `MISMATCH` atomically, then `complete(key, Stored)` or `release(key)`. The default holds 10000 entries with a 24 h TTL, expires lazily, and evicts the least recently used completed entry; running entries are never evicted. `.ttl` configures only the default store.
- The response is already buffered by `Response`, so storing keeps a reference to the body bytes rather than copying them.

## Consequences
- jolt-core stays dependency-free (`java.security` only). Apps that do not install it pay nothing.
- Core grows by `Idempotency` and `IdempotencyStore` (with the nested `Claim`, `State` and `Stored`), at one `llms-extras.txt` line.
- Requests with the same key arriving while another runs are rejected with 409 rather than queued, so clients must retry.
- The in-memory store is per process; a restart or a second instance forgets keys.
