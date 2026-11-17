# ADR 0011: route constraints, request ids, sessions, CSRF and refresh tokens

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Five gaps every real app hits: validating path parameters (#21), correlating logs (#31), keeping a login across requests (#26), blocking cross-site form posts (#25) and long-lived logins with short-lived JWTs (#29).

## Decision
- Routing: `:id(\d+)` regex constraints and `:name?` optional last params are new pattern syntax, not new methods. Constraints compile once at registration; only nodes that have one pay for a match. Precedence is static, constrained, plain, wildcard. A miss falls through and ends in the normal 404.
- `RequestId.middleware()` and `Request.id()`; `Logger.requests()` appends the id when set.
- `Sessions.signed(secret)` plus `Sessions.of(req)` and `Session` (get, put, remove, clear, isNew). State lives in an HMAC-SHA256 cookie, so there is no store SPI and no server memory. Values are strings, numbers and booleans, capped at 3072 encoded bytes so the cookie stays under the 4096-byte browser limit.
- `Csrf.protect()` with `.token()` and `.strict()`, and `Csrf.token(req)`. Fetch Metadata and Origin checks need no state; the double-submit token is opt-in.
- `RefreshTokens` and a `RefreshStore` SPI in jolt-auth. The SPI is justified because production deployments need a shared store; the in-memory default is bounded.

## Consequences
- jolt-core stays dependency-free (JDK `javax.crypto` and `java.security` only). Nothing is installed unless the app opts in, so apps that do not use these pay nothing per request.
- Core grows by 4 classes (`RequestId`, `Sessions`, `Session`, `Csrf`) and jolt-auth by 3 (`RefreshTokens`, `RefreshStore`, plus the nested `Pair` and `Entry` records), at one `llms-extras.txt` line each.
- Cookie sessions are signed, not encrypted, and cannot be revoked server-side before expiry; use a server store for that.
- CSRF checks rely on browsers sending Fetch Metadata or Origin; a cookie-bearing request with neither is rejected.
