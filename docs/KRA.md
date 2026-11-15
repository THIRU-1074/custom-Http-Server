# Jolt v0.1.0: Key Result Areas

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
