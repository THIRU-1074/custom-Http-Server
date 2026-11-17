# ADR 0006: security headers, roles, password hashing and test sessions

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Four small additions close gaps every real app hits: browser hardening headers (#24), role checks (#27), password hashing (#28) and cookie-aware testing (#32).

## Decision
- `SecurityHeaders.defaults()` in jolt-core: opt-in middleware, five safe headers, `.hsts(Duration)`, `.csp(String)`, and `.https(Predicate<Request>)`. HSTS is sent only when the predicate accepts the request; the default predicate is false, so HSTS never goes out over plain HTTP. Values are validated when configured.
- `Auth.requireRole(...)` and `Auth.requireAll(...)` in jolt-auth: read the `role` or `roles` claim that `JwtAuth` stores in `req.attr("claims")`, or that `BasicAuth.require(verifier, user -> roles)` sets; 401 without a user, 403 without the role. Route-level use will follow once route-level middleware lands; until then use `app.use(prefix, mw)`.
- `PasswordHasher` moves from the attendance example into jolt-auth (static `hash`, `verify`, `needsRehash`, default 210000 iterations) with the stored format unchanged.
- `JoltTest.session()` keeps cookies between calls (Max-Age=0 deletes); `Result.jsonField(path)` and `jsonEquals(path, expected)` use the built-in JSON parser.

## Consequences
- jolt-core stays dependency-free; nothing is installed unless the app opts in, so apps that do not use these pay nothing per request.
- Public surface grows by 2 classes in core (`SecurityHeaders`, plus methods on `JoltTest`) and 2 in jolt-auth (`Auth`, `PasswordHasher`), at one llms.txt line each.
- Cookies in test sessions match by name only (single host); roles are read from claims only (plain Basic auth has none), with no role hierarchy.
