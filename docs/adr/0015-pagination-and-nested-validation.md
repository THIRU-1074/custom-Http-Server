# ADR 0015: pagination helper and nested validation

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. A ticket-booking app (BookMyShow-style) shows two gaps: every list endpoint parses, caps and reports `page` and `size` by hand (#57), and `Rules` validates flat fields only, so a booking with a list of seats cannot report `seats[2].row` (#61).

## Decision
- `Page` (#57), a record `(page, size)` with `Page.of(params)` and `Page.of(params, defaultSize, maxSize)`. It takes `Params`, so `Request` and typed handlers share it. Defaults are page 1, size 20, cap 100: a list endpoint is bounded with no configuration. A size over the cap is clamped, not rejected, so clients asking for "everything" still get an answer; a malformed or below-1 value is a 400 naming the parameter, like `Params.query`. `offset()` is a `long` computed in 64 bits, so no page number overflows it. `page.result(items, total)` returns `Page.Result`, a record the built-in JSON writes as `{items, page, size, total, totalPages}`. `Result.link(req, res)` sets an RFC 8288 `Link` header with relative `first`, `prev`, `next` and `last` URIs; other query parameters keep their order and repeats and are re-encoded (RFC 3986 unreserved set, spaces as `%20`), and `page` and `size` are appended. Typed handlers have no `Response`, so they return the result without a `Link` header. No `JoltConfig` field: the limits belong to each endpoint.

## Consequences
- jolt-core stays dependency-free; nothing runs unless a handler calls these helpers, so the request hot path is unchanged.
- One `llms-extras.txt` line per feature.
- The `Link` URIs are relative to the request path; behind a path-rewriting proxy they reflect the path Jolt saw.
