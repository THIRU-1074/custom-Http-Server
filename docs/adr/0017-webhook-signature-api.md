# ADR 0017: Webhook signature verification middleware

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Payment gateways call back with an HMAC over the raw body, and apps must verify it before trusting the call (#59). Hand-rolled checks tend to compare with `equals`, parse the body first, or skip replay protection.

## Decision
- `Webhooks.hmacSha256(secret, header)` is an opt-in `Middleware`, in jolt-core beside `Csrf` and `Sessions`. The secret is `byte[]` (copied) or text (UTF-8) and must be at least 32 bytes, else `IllegalArgumentException`, the same bar as `Csrf.token` and `Sessions.signed`.
- The header carries the MAC as hex (64 characters, any case) or base64 (standard alphabet, 32 bytes). `.prefix(String)` requires a leading scheme such as `v1=`; it defaults to none.
- Without `.timestampHeader(name)` the MAC covers the raw body. With it, the header must hold unix seconds and the MAC covers `timestamp + "." + body`; a missing, non-numeric, or out-of-window stamp is rejected. `.tolerance(Duration)` (positive, default 5 minutes) applies in both directions; `.clock(Clock)` makes it testable.
- The comparison is `MessageDigest.isEqual`. The MAC is computed before the window is judged, and every failure is the same 401 "Invalid webhook signature", so a caller learns nothing about which check failed.
- The middleware reads `Request.body()` without consuming it, so the handler still gets the exact bytes, as required for re-parsing or storing the event.

## Consequences
- jolt-core stays dependency-free (`javax.crypto` only). Apps that do not install it pay nothing.
- Core grows by one class and one `llms-extras.txt` line.
- Providers that pack timestamp and signature into one header (for example `t=...,v1=...`) are not parsed; an app can split the header in a preceding middleware or wait for a follow-up.
- Only HMAC-SHA256 ships; other algorithms need a new factory and a new decision.
- Timestamps cannot stop a replay inside the window; handlers needing exactly-once should pair this with `Idempotency` or event-id de-duplication.
