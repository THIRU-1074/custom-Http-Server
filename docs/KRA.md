# Jolt: Key Result Areas

This file has two parts: the v0.1.0 baseline and review (below), and the [v0.3.1 outcomes](#v031-outcomes) that closed it.

## v0.1.0 baseline

Baseline: tag `v0.1.0` (commit `e4361a2`), built 14 Jun – 21 Jul 2025.
Scope: a from-scratch HTTP/1.1 server and Express-style framework in Java, using only `java.net.ServerSocket` (no servlet container and no Netty).

| # | Key Result Area | Outcome |
|---|---|---|
| 1 | HTTP/1.1 protocol engine | Parses requests from raw TCP bytes and writes compliant responses |
| 2 | Express-style routing | `GET/POST/PUT/ALL` handlers, path and query params, mountable routers |
| 3 | Authentication | HTTP Basic (DB-backed) and JWT (HS256) issue and verify |
| 4 | Content handling | JSON, HTML, plain text and image bodies, in and out |
| 5 | Concurrency and connection management | Concurrent clients, keep-alive, ordered pipelined responses |
| 6 | Real applications on the framework | Attendance app and webhook broadcast service |
| 7 | Load-tested | 50 VUs for 1 min, 0% errors |

---

## 1. HTTP/1.1 protocol engine

- **Built:** request-line, header and body parsing directly from the socket `InputStream`, and response serialization with status line, headers, `Content-Length` and body.
- **Iteration:** the first parser read characters and broke on binary bodies. It was rewritten to parse **bytes**: it finds `\r\n\r\n`, decodes headers as ISO-8859-1 and keeps the body as raw `byte[]`.
- **Evidence:** `RequestHandler.parseRequest()`, `Response.serialize()`. Commits `e4361a2` (byte parser) and `3e0acb1` (case-insensitive header normalization).
- **Outcome:** handles HTTP/1.0 and 1.1 clients (browsers, Postman, curl), including small binary uploads. Large or slow bodies can be truncated (L7).

## 2. Express-style routing

- **Built:**
  - `Jolt.GET/POST/PUT/ALL(path, handlers...)`.
  - `:param` path segments, and query-string parsing into `req.urlQueryParams`.
  - `Router` instances mounted with `Jolt.use("/prefix", router)`.
- **Why it matters:** routing looks like Express, so a student who knows Node can read and write it with no Spring-style annotations, beans or container.
- **Evidence:** `Jolt.java`, `Router.java`. Commits `5f34fc9` (routers), `7dbab60` (path and query params), `9015be1` (handler fix).
- **Outcome:** teams can build separate routers (for example `/auth`) and combine them in one app. Caveats: only the first handler passed to a route runs (L4), and a first-registered `PUT` route fails (L5).

## 3. Authentication

- **Built:**
  - HTTP Basic: decodes `Authorization: Basic`, checks credentials in SQLite through a parameterized `PreparedStatement` (no SQL injection), and returns `WWW-Authenticate` on 401.
  - JWT: issues HS256 tokens with `sub`, `role`, `iat` and `exp` claims, and verifies them. Expired, bad-signature and malformed tokens get distinct error reasons.
  - `Jolt.authorizeClient(req, res, realm)` guards a route in one line.
- **Evidence:** `Auth.java`, `Request.authorize()`. Commits `864883f`, `4de6d8d`, `a830b03`.

## 4. Content handling

- **Built:** a polymorphic `Content` hierarchy (`JSON`, `text`, `html`, `image`). It sets `Content-Type` automatically through a Java pattern-matching `switch`, and computes `Content-Length` from the serialized bytes.
- **Evidence:** `Content.java`, `Response.serialize()`.
- **Outcome:** routes serve HTML pages, a favicon and JSON APIs, and accept JSON and PNG/JPEG uploads.

## 5. Concurrency and connection management

- **Built:**
  - A thread per client connection.
  - A request-handler thread per request.
  - A per-connection responder thread that drains a **response queue in arrival order**, so pipelined keep-alive requests are answered in order.
  - Socket read timeout of 120 s.
- **Evidence:** `ClientConnection.java`, `ClientResponder.java`. Commits `b849db1` (multithreading), `b3399e4` (multi-socket fix).
- **Outcome:** several browsers and Postman runners served at the same time with consistent response order in testing. The shared queue is not synchronized (L3), so this is not guaranteed under heavy concurrency.

## 6. Real applications built on Jolt

- **Attendance app** (`AttendanceApp.java`): an HTML UI, a login router under `/auth`, JWT issue on login, and a SQLite credential store.
- **Webhook notification service** (`NotificationServer.java`): authenticated webhook URL registration, and a broadcast of company events to every registered URL through `java.net.http.HttpClient`.
- **Evidence:** commit `33cfd49`.
- **Outcome:** the framework is proven on two different kinds of apps, a browser-facing app and a server-to-server service.

## 7. Load test

Postman Performance run (`REST-API-basics--CRUD,-test-&-variable-performance-report-8.html`, `Jolt-Metrics.png`, commit `90bc424`):

| Virtual users | Duration | Requests | Throughput | Avg response | Error rate |
|---|---|---|---|---|---|
| 50 | 1 min | 1,860 | 27.45 req/s | 295 ms | **0.00 %** |

Zero errors under 50 concurrent users. The likely cause of the modest throughput is the sleep-polling design (L1), but this has not been profiled. SQLite locking and the test client are other candidates. The v1 benchmark will measure before and after.

---

## Known limitations at v0.1.0

These were found by reviewing the baseline. They define the v1 roadmap. Locations name methods, not line numbers, so they stay valid as the code changes.

| ID | Area | Limitation | Location |
|---|---|---|---|
| L1 | Performance | Threads sleep-poll (`Thread.sleep(250/500)`) for readiness, which adds up to ~0.75 s latency and limits throughput | `ClientConnection.handleClient`, `ClientResponder.provideResponse` |
| L2 | Concurrency | Unbounded threads: 2 per connection plus 1 per request | `Jolt.listen`, `ClientConnection.handleClient` |
| L3 | Concurrency | `LinkedList` queue and `isReady` flags shared across threads with no synchronization or `volatile` | `ClientResponder` constructor |
| L4 | Correctness | Middleware chain runs only the first handler (`while (i == callBackLen)`) | `Jolt.handleRequest` |
| L5 | Correctness | `PUT` registration initializes the POST map, so an app whose first route is PUT throws `NullPointerException` | `Jolt.PUT` |
| L6 | Correctness | A handler exception never marks the response ready, so the connection hangs | `Jolt.handleRequest` (no try/catch) |
| L7 | Correctness | Body read stops at `in.available()==0`, not at `Content-Length`, so large bodies can be truncated | `RequestHandler.parseRequest` |
| L8 | Correctness | Cookies read from `Cookies` (should be `Cookie`), and multiple `Set-Cookie` values joined into one header | `RequestHandler.parseCookies`, `Response.setCookie` |
| L9 | Correctness | Router prefix match depends on `HashMap` order, and DELETE/PATCH are unsupported | `Jolt.handleRequest` |
| L10 | Security | Hardcoded JWT secret, and plaintext passwords in the DB | `Auth.SECRET`; `credentials` table, `AttendanceApp.db` is tracked in git |
| L11 | Security | No limits on header or body size, and no slowloris protection | `RequestHandler.parseRequest` |
| L12 | Security | Credentials, tokens and full requests printed to stdout | `Request.authorize`, `RequestHandler.parseRequest` |
| L13 | Security | Uploads written to a fixed path inside `src/main/resources`, and wildcard CORS hardcoded | `RequestHandler.parseRequest` |
| L14 | Packaging | Not importable: static global route tables, demo apps in the framework package, build output and DB committed | `Router` static handler maps |
| L15 | Quality | No automated tests | — |

## Skills demonstrated

- HTTP/1.1 message framing at the byte level.
- Socket programming.
- Thread-based concurrency and ordering guarantees.
- Token-based and Basic authentication.
- JDBC with prepared statements.
- API design modelled on Express.
- Performance testing with Postman.
- Debugging protocol-level issues (header casing, binary bodies).

---

## v0.3.1 outcomes

v0.1.0 was a raw `ServerSocket` server with 15 documented limitations. The code was then rewritten as a multi-module Java 21 library (`jolt-core` with no dependencies, plus `jolt-auth`, `jolt-mcp`, `jolt-db`, `jolt-dev`, `jolt-archetype`, examples and `jolt-starter`). It is published through JitPack as `com.github.THIRU-1074.custom-Http-Server:<module>:v0.3.1`. `v0.2.0` also exists. The v0.3.0 tag failed to build and has no artifacts.

### L1-L15 closed

The rewrite landed through merged branches before PR numbering started, so fixes are cited by commit. All 15 are closed in the current code. Checked by reading the history and the source.

| ID | Closed by | How |
|---|---|---|
| L1 sleep-polling | `fb9ce2e` | Blocking reads on a virtual thread per connection; the only `Thread.sleep` left in library code is the accept-error backoff in `Server` |
| L2 unbounded threads | `fb9ce2e`, `b73f2ce` | `Semaphore(maxConnections)`; extra connections get 503; flood-and-recover test |
| L3 unsynchronized queue | `fb9ce2e` | No response queue: one thread owns a connection and handles requests in order, so pipelined responses stay ordered |
| L4 first handler only | `c96f138` | `Router` runs the middleware chain with `next` |
| L5 PUT registration | `c96f138`, `3924f70` | PUT uses the same registration as every method; regression test |
| L6 hung connection on exception | `fb9ce2e`, `0ef4eda`, `3924f70` | Exceptions become a 500 (or the `HttpException` status); middleware headers are kept; tested over a socket |
| L7 truncated bodies | `25ecea8`, `87f750b` | `HttpParser` reads exactly `Content-Length` bytes or a chunked body, in chunks |
| L8 cookies | `a490bc2`, `3924f70`, `5d5f63f` | Reads `Cookie` (duplicates joined with `; `), one `Set-Cookie` per cookie, names and attributes validated |
| L9 router order, DELETE/PATCH | `b5eba41`, `c96f138` | `RouteTrie` (static, then `:param`, then `*`), longest-prefix mounting, DELETE and PATCH supported |
| L10 secrets and plaintext passwords | `1b563fc`, `898c128` (PR #2), `40faa7e`, `e90efbc` | No default JWT secret, 32-byte minimum; example passwords hashed with PBKDF2 and admin seeded from env; DB and build output untracked |
| L11 no limits | `381f15c`, `fd0855b`, `52f960c`, `b0c3968` | Request line, header, body and connection limits, read and keep-alive timeouts, smuggling and bare CR/LF rejection, bounded chunk trailers |
| L12 secrets in logs | `06c8f09`, `d3f0e2f` | `System.Logger` only, logged fields sanitized, `Logger.requests()` logs no query, headers or bodies; no `System.out` in `jolt-core`, `jolt-auth`, `jolt-mcp` or `jolt-db` (the `jolt-dev` runner prints its restart notice) |
| L13 uploads and wildcard CORS | `aa76f5b`, `08a77f5`, `e72cbaf` | No fixed upload path (multipart is read in memory); `sendFile` and `Static` confined to a root; CORS is opt-in with an exact origin |
| L14 not importable | `1403bcc`, `2fee3d0`, `e908fda`, `e90efbc` | Maven multi-module build, no static route tables, v0.1 sources removed, demos moved to `examples/` |
| L15 no tests | `3924f70`, `53c0a7b`, `5a48c9f`, `c2595e2`, `94883d8` | In-memory `JoltTest`, loopback protocol tests, edge tables, example smoke tests |

### The 30 feature issues

29 of 30 are implemented and closed. Each PR was reviewed on its branch and findings were fixed before merging.

| PR | Issues |
|---|---|
| [#40](https://github.com/THIRU-1074/custom-Http-Server/pull/40) | #24 security headers, #27 roles, #28 `PasswordHasher`, #32 `JoltTest` v2 |
| [#41](https://github.com/THIRU-1074/custom-Http-Server/pull/41) | #13 typed params, #20 route middleware, #22 validation, #23 problem+json |
| [#42](https://github.com/THIRU-1074/custom-Http-Server/pull/42) | #7 Expect: 100-continue, #9 trusted proxy, #12 multipart, #14 JSON time, UUID, enum, Optional |
| [#43](https://github.com/THIRU-1074/custom-Http-Server/pull/43) | #15 gzip, #16 ETag and cache, #17 static v2, #18 negotiation and codecs, #19 templating |
| [#44](https://github.com/THIRU-1074/custom-Http-Server/pull/44) | #33 dev mode, #34 archetype, #35 `jolt-db`; plus release preparation |
| [#45](https://github.com/THIRU-1074/custom-Http-Server/pull/45) | #21 route constraints, #25 CSRF, #26 signed sessions, #29 refresh tokens, #31 request IDs |
| [#46](https://github.com/THIRU-1074/custom-Http-Server/pull/46) | #8 TLS, #10 SSE and streaming, #11 WebSocket, #30 health and metrics |

Supporting PRs: #3 Express parity, #4 SOLID support, #2 cleanup and hardening, #5 benchmarks, #6 starter, #37-#39 guards and the `llms.txt` budget, #47-#48 release and JitPack build fixes. Public API growth is justified in ADRs 0001-0011 (two files share the number 0002).

### Measured results

Tests at the final main: `jolt-core` 783, `jolt-auth` 47, `jolt-mcp` 9, `jolt-db` 14, `jolt-dev` 15, examples 9, 1 and 25. JaCoCo gates `jolt-core` at 80% line coverage (about 96% measured earlier). CI on JDK 21 and Linux runs `verify` and `token-guard`.

| Measure | Result |
|---|---|
| Token cost of a todos API (auth, validation, one test), tiktoken `cl100k_base` | Express 789, Jolt 1017, Javalin 1172, Spring Boot 1320. Jolt is about 23% below Spring Boot and 13% below Javalin; Express is smaller |
| Jolt todo app (source plus pom, no tests) | 697 tokens, unchanged after 29 features (token-guard baseline, +0.0%) |
| `llms.txt` / `llms-extras.txt` | 2054 tokens (limit 2100) / 1647 tokens (limit 3200) |
| Throughput, authenticated `GET /todos`, 50 clients, 20 s x 3 runs after warmup | v0.2.0 median 22,337 req/s (21.2k to 22.6k); current main 22,561 and 21,887. No regression within noise |

Throughput came from one idle Windows machine (AMD Ryzen 5 5625U, JDK 24) and is indicative only. The v0.1.0 Postman figure (27.45 req/s, 295 ms, 50 VU) used a different tool and is not comparable. In the earlier cross-framework run Jolt, Spring Boot and Javalin were statistically tied and Express was about 5x lower. Details: [benchmarks/RESULTS.md](../benchmarks/RESULTS.md).

### Still open

- **Maven Central (#36):** needs the owner's namespace verification, Central account and GPG key. The deploy and signing steps have not been run ([RELEASING.md](RELEASING.md)).
- Linux and macOS performance is not measured.
- The Autobahn WebSocket conformance suite has not been run.
- TLS with client certificates is untested.
- `JoltTest` cannot stream or upgrade to WebSocket.
- MCP was checked with MCP Inspector 1.0.5 only.
