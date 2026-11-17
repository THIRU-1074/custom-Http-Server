# ADR 0005: Protocol and body API growth

Status: accepted

## Context

Four gaps made everyday servers hand-roll protocol or parsing code: clients pausing about a second on `Expect: 100-continue`, no way to read file uploads, JSON records that could not hold dates, UUIDs, decimals or optional fields, and no safe way to see the real client address behind a reverse proxy (so `RateLimit.perIp` limited the proxy, not the client).

## Decision

Add, JDK-only, with safe defaults and nothing paid by requests that do not use the feature:

1. `Expect: 100-continue` (no public API). After the body framing is validated, the server sends `100 Continue` and then reads the body. A declared `Content-Length` over `maxBodyBytes`, a malformed or duplicated length, a length with `Transfer-Encoding`, or an unsupported `Transfer-Encoding` gets its final error with no `100`. A chunked body has no declared size, so it gets the `100` and is limited while it is read (413 if it grows too large). Other expectations get 417; HTTP/1.0 ignores it. New `HttpStatus.EXPECTATION_FAILED`.
2. JSON binding (no public API). The built-in mapper reads and writes `Instant`, `LocalDate`, `LocalDateTime`, `UUID`, `BigDecimal`, enums (by name) and `Optional`. Invalid values raise `IllegalArgumentException`, so requests get 400. Typed `BigDecimal` and `BigInteger` targets get the exact number text (exponent magnitude over 400 is rejected); untyped targets still get `Long` or `Double`.
3. `Request.parts()` returning `List<Part>` (name, filename, contentType, bytes, text), plus `JoltConfig.maxParts` (10) and `maxPartBytes` (1 MB). Strict RFC 7578 parsing over the body already bounded by `maxBodyBytes`, in memory only, file names reduced to a bare name. 413 over a limit, 400 malformed, 415 for other content types.
4. `JoltConfig.trustProxy(String...)` (default none), `Request.scheme()` and `Request.host()`. Only a peer matching a listed address or CIDR block has `X-Forwarded-For` (rightmost untrusted entry), `-Proto` and `-Host` applied; everyone else is ignored. `Request` keeps its fixed values (peer address, scheme, host, the `JoltConfig` that bounds `parts()`) in constructor-set final fields, like `remoteAddress`. Lazily parsed views (`query`, `form`, `parts`) are cached for the one thread that handles the request and are not synchronized.

## Consequences

- Public API grows by one class (`Part`), four `Request` methods (`parts`, `scheme`, `host`, two constructors), three `JoltConfig` methods and one `HttpStatus` constant.
- No new dependencies; `llms.txt` gains four lines in one block.
- Uploads are memory-only: peak use per request is bounded by `maxBodyBytes`. Temp-file spooling is a possible later change.
- Trusting a proxy is explicit; a mistaken broad block lets that network spoof client addresses.
