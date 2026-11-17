# ADR 0005: Protocol and body API growth

Status: accepted

## Context

Four gaps made everyday servers hand-roll protocol or parsing code: clients pausing about a second on `Expect: 100-continue`, no way to read file uploads, JSON records that could not hold dates, UUIDs, decimals or optional fields, and no safe way to see the real client address behind a reverse proxy (so `RateLimit.perIp` limited the proxy, not the client).

## Decision

Add, JDK-only, with safe defaults and nothing paid by requests that do not use the feature:

1. `Expect: 100-continue` (no public API). The server sends `100 Continue` before reading a body that fits `maxBodyBytes`; an oversize declared length gets 413 unread; other expectations get 417; HTTP/1.0 ignores it. New `HttpStatus.EXPECTATION_FAILED`.
2. JSON binding (no public API). The built-in mapper reads and writes `Instant`, `LocalDate`, `LocalDateTime`, `UUID`, `BigDecimal`, enums (by name) and `Optional`. Invalid values raise `IllegalArgumentException`, so requests get 400. Typed `BigDecimal` and `BigInteger` targets get the exact number text (exponent magnitude over 400 is rejected); untyped targets still get `Long` or `Double`.
3. `Request.parts()` returning `List<Part>` (name, filename, contentType, bytes, text), plus `JoltConfig.maxParts` (10) and `maxPartBytes` (1 MB). Strict RFC 7578 parsing over the body already bounded by `maxBodyBytes`, in memory only, file names reduced to a bare name. 413 over a limit, 400 malformed, 415 for other content types.
4. `JoltConfig.trustProxy(String...)` (default none), `Request.scheme()` and `Request.host()`. Only a peer matching a listed address or CIDR block has `X-Forwarded-For` (rightmost untrusted entry), `-Proto` and `-Host` applied; everyone else is ignored. `Request` stays immutable: the values arrive through the constructor, like `remoteAddress`.

## Consequences

- Public API grows by one class (`Part`), four `Request` methods (`parts`, `scheme`, `host`, one constructor), three `JoltConfig` methods and one `HttpStatus` constant.
- No new dependencies; `llms.txt` gains four lines in one block.
- Uploads are memory-only: peak use per request is bounded by `maxBodyBytes`. Temp-file spooling is a possible later change.
- Trusting a proxy is explicit; a mistaken broad block lets that network spoof client addresses.
