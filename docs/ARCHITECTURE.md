# Architecture

> This describes the **v1 target design**. The code at v0.1.0 does not match it yet: it has static route tables, thread-per-stage connection handling and no module split. The current state is documented in KRA.md on the `docs/kra-readme` branch.

## Module map

```
pom.xml                      parent (Java 21, Spotless, JUnit 5, JaCoCo)
jolt-core/                   zero dependencies; the only thing users must import
  in.jolt                    Jolt, App, Request, Response, Handler, Middleware, Router,
                             HttpStatus, JoltConfig, HttpException
  in.jolt.internal.http      HttpParser, ConnectionHandler, ResponseWriter, Limits
  in.jolt.internal.routing   RouteTrie
  in.jolt.internal.json      Json (minimal parser/writer), behind the JsonMapper SPI
  in.jolt.test               JoltTest (in-memory test client)
jolt-auth/                   JwtAuth, BasicAuth middleware (depends on java-jwt)
jolt-mcp/                    Routes-as-MCP-tools bridge (depends on core only)
examples/attendance          ported Attendance app (SQLite lives here only)
examples/webhook             ported Webhook broadcaster
benchmarks/tokens/           token-cost comparison
```

Packages named `internal` are not API.

## Request lifecycle

1. `Jolt.create(config)` builds an `App`; `listen(port)` starts the accept loop and returns a `Server` with `close()`.
2. Each accepted connection acquires a permit from `Semaphore(maxConnections)`. Without a permit the server answers 503 and closes.
3. A virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`) owns the connection and handles its requests sequentially, which preserves HTTP/1.1 response order.
4. `HttpParser` reads headers up to `\r\n\r\n` under size caps, then exactly `Content-Length` bytes (or a chunked body). Malformed input gets a 4xx.
5. `RouteTrie` resolves method and path to a handler chain, binding `:params`.
6. The middleware chain runs `(req, res, next)`; the route handler is the last link.
7. An uncaught exception becomes a 500 with a generic body, or the status of an `HttpException`. The stack trace goes to the logger only.
8. `ResponseWriter` serializes the response, rejecting CR/LF in header names and values, then the connection loops or closes on keep-alive limits.

## Design patterns

| Pattern | Where |
|---|---|
| Chain of Responsibility | Middleware `(req, res, next)` |
| Composite | `Router` mounted into `App` or into other routers |
| Strategy | `JsonMapper` SPI (built-in or Jackson); `Authenticator` in `jolt-auth` |
| Builder | `JoltConfig`; fluent `Response` (`status().header().json()`) |
| Facade | `Jolt` / `App` hide the parser, executor and writer |
| Trie | `RouteTrie`: matching in O(path segments), with `:params` and `*` wildcard |

## Target API

```java
var app = Jolt.create(JoltConfig.defaults());
app.get("/users/:id", (req, res) -> res.json(repo.find(req.param("id"))));
app.use("/auth", authRouter);
app.listen(8080);
```
