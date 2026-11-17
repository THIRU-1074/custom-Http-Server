# ADR 0008: response-side features and their API growth

## Status
Accepted.

## Context
Constitution rules 3 and 6 require an ADR for public API growth. Five response-side features were requested (issues 15 to 19): gzip, ETag and Cache-Control, static files v2, content negotiation and HTML templates. Servers built on Jolt need them to serve real front ends, and an AI tool should be able to use them in one line each.

## Decision
Grow the API by the smallest surface that covers each case, keep every feature opt-in, and leave the request hot path untouched for apps that do not use them.

| Feature | Public API added |
| --- | --- |
| gzip | `Compress.gzip()`, `Compress.gzip(int minBytes)` (a middleware) |
| ETag and caching | `res.etag()`, `res.cache(Duration)`, `res.noStore()` |
| Static v2 | `Static.serve(...)` now returns `Static` (still a `Middleware`) with `spaFallback(String)`; `sendFile` gains validators and ranges |
| Negotiation | `BodyCodec` interface, `app.codec(BodyCodec)`, `res.send(Object)` |
| Templates | `app.views(Path)`, `app.views(Path, String layout)`, `res.render(String, Map)` |

Design choices:
- No `JoltConfig` fields. Each feature is registered where it is used, so config stays small and unchanged.
- The only per-request cost is two field assignments in `App.dispatch` (the request and response learn about each other and the app). Compression, codecs and views are looked up only when a handler asks for them.
- `BodyCodec` is the one new SPI (PRINCIPLES.md item 5). JSON stays built in and `res.json` and `req.body(Class)` do not change when no codec is registered. Once a codec is registered, an unknown request `Content-Type` is a 415; before that, behaviour is exactly as before so existing apps are not broken.
- Conditional requests (304) are decided when `res.etag()` runs or a file is sent, not for every response, so handlers that never opt in pay nothing. Static files get ETag and Last-Modified automatically.
- Static v2 keeps every guarantee: traversal 403, dotfiles 404, size cap, symlink escape. The SPA fallback is off by default and answers only browser navigations the router had no route for, so API and handler 404s stay 404.
- Templates have no dependency and no expression language: escaped output by default, no code execution, names confined to the views root, and only records and maps are read (record reflection already exists for JSON binding).
- Compression documents the BREACH risk on the `Compress` Javadoc and in `llms-extras.txt`.
- Docs: one line per feature under `## Response` in `llms-extras.txt`. `llms.txt` is unchanged.

## Consequences
- About a dozen public methods and one interface, none on the hot path of apps that skip them.
- Multiple ranges, brotli, and template expressions or helpers are not supported; each would need its own decision.
- `HttpStatus` gains 206, 406 and 416.
