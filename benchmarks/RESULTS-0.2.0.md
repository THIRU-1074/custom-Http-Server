# Benchmark results

Measured 2026-11-16. Machine: AMD Ryzen 5 5625U (6 cores / 12 threads), 13.8 GB RAM, Windows 11.
JDK: Oracle OpenJDK 24.0.1 (modules compiled with `--release 21`). Node v22.16.0, Maven 3.9.10,
Python 3.13. Client and server ran on the same machine, so the client competes with the server for CPU.
Jolt is resolved as `in.jolt:jolt-*:0.2.0` from the local Maven repository (installed from this repo).

## 1. Token counts (todo app: source + tests + build file)

Tokenizer: `tiktoken` 0.14.0, `cl100k_base` (installed with `pip install tiktoken`; succeeded).

```
python benchmarks/tokens/count.py
```

| framework | source | test | build | total | vs jolt |
|---|---:|---:|---:|---:|---:|
| jolt | 510 | 320 | 187 | 1017 | 1.00x |
| spring | 643 | 319 | 358 | 1320 | 1.30x |
| javalin | 535 | 237 | 400 | 1172 | 1.15x |
| express | 488 | 228 | 73 | 789 | 0.78x |

Findings: Jolt is smaller than Spring Boot and Javalin, but **Express is smaller than Jolt (0.78x)**.
The earlier chars/4 estimate (jolt 1129, spring 1708, javalin 1391, express 703) overstated the
gap to Spring and Javalin. Any claim of "fewer tokens than every other framework" is false;
"fewer than the Java frameworks" holds. Express excluded `node_modules` and `package-lock.json`.
The per-framework unit tests were also run and pass (Maven `package` for jolt, spring, javalin;
`node --test` for express).

## 2. Behaviour verification (same request sequence against every app)

```
java benchmarks/verify/Check.java http://localhost:8080
```

Sequence: GET /todos without auth, POST blank title, POST create, GET /todos/1, GET /todos/999, DELETE /todos/1.

| step | expect | jolt | spring | javalin | express |
|---|---|---|---|---|---|
| GET /todos no auth | 401 | 401 PASS | 401 PASS | 401 PASS | 401 PASS |
| POST invalid | 400 | 400 PASS | 400 PASS | 400 PASS | 400 PASS |
| POST create | 201 | 201 PASS | 201 PASS | 201 PASS | 201 PASS |
| GET /todos/1 | 200 | 200 PASS | 200 PASS | 200 PASS | 200 PASS |
| GET /todos/999 | 404 | 404 PASS | 404 PASS | 404 PASS | 404 PASS |
| DELETE /todos/1 | 204 | 204 PASS | 204 PASS | 204 PASS | 204 PASS |

All four pass; no benchmark source needed fixing, so the token counts above were not redone.
Each app was started on port 8080: Jolt/Javalin with `java -cp target/classes;<deps> todo.TodoApp`
(deps from `mvn dependency:build-classpath`), Spring with `java -jar target/todo-spring-1.0.jar`,
Express with `node app.js` (after `npm install`, express 4.x).

## 3. Load test

```
java benchmarks/load/Load.java http://localhost:8080/todos 50 30 "Authorization: Bearer secret"
```

50 concurrent virtual-thread clients, 30 s, `GET /todos` (authenticated, empty list), JDK `HttpClient`
over HTTP/1.1, apps started fresh and run one at a time. Load.java gained an optional header argument
(the apps return 401 without the bearer token); the 50 clients / 30 s profile was already supported.
Runs are consecutive on the same JVM (run 1 includes JIT warm-up). 0 errors in every run.

| app | run | req/s | mean latency |
|---|---|---:|---:|
| Jolt todo | 1 | 16178 | 3.1 ms |
| Jolt todo | 2 | 25604 | 2.0 ms |
| Jolt todo | 3 | 21836 | 2.3 ms |
| Spring Boot todo | 1 | 20657 | 2.4 ms |
| Spring Boot todo | 2 | 16357 | 3.1 ms |
| Spring Boot todo | 3 | 16021 | 3.1 ms |
| Javalin todo | 1 | 16111 | 3.1 ms |
| Javalin todo | 2 | 15523 | 3.2 ms |
| Express todo | 1 | 3405 | 14.7 ms |

Run 1 for Jolt and Spring is the first launch of each app; Jolt runs 2-3 and Spring runs 2-3 come from a
second launch (runs 2 and 3 share one JVM). Express has a single run.
Javalin had an additional earlier run of 13671 req/s / 3.7 ms that was not used, because a stop command
may have overlapped it; it was rerun (the two Javalin rows).

Interpretation: run-to-run variance (about 16k to 26k req/s for Jolt) is larger than the gap between Jolt,
Spring Boot and Javalin, so **no ranking among those three is supported by this data**; all are in
the same order of magnitude, Express is roughly 5x lower. Single-machine, single-route, no warm-up control.

v0.1 baseline: 27.45 req/s, 295 ms average, 50 VU, 1 minute, measured with Postman. The tools differ
(Postman's own overhead and a different route and client versus this JDK client), so the comparison
is only indicative; the roughly 600x throughput difference should not be read as a pure framework improvement.

## 4. MCP check

Attendance example does not expose MCP, so a minimal demo was added at `benchmarks/mcp`
(`demo.McpDemo`: one `.tool("Fetch a todo by id")` route, `Mcp.expose(app, "/mcp", ...allowOrigins("http://localhost:3000"))`, port 8090).

```
cd benchmarks/mcp && mvn compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes;$(cat target/cp.txt)" demo.McpDemo
npx -y @modelcontextprotocol/inspector --cli http://localhost:8090/mcp --method tools/list
npx -y @modelcontextprotocol/inspector --cli http://localhost:8090/mcp --method tools/call --tool-name get_todos_id --tool-arg id=7
curl -X POST http://localhost:8090/mcp -H "Origin: http://evil.example" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

| check | result |
|---|---|
| inspector tools/list | PASS: one tool `get_todos_id`, description "Fetch a todo by id", schema requires `id` |
| inspector tools/call id=7 | PASS: `{"id":7,"title":"demo"}`, `isError: false` |
| bad Origin `http://evil.example` | PASS: HTTP 403 |
| allowed Origin `http://localhost:3000` | HTTP 200 |

Inspector version used: 1.0.5 (what `npx` resolved; it prints a v1-deprecated notice; v2 was not tried).
The bad-Origin check used curl, since the inspector CLI does not set arbitrary Origin headers.

## Not verified

- Load comparison is not apples to apples with v0.1 (different tool, route, and hardware unknown for v0.1).
- No ranking between Jolt, Spring and Javalin (variance exceeds the gap); no memory or latency percentiles measured.
- MCP was tested with inspector v1.0.5 only, and against a new demo, not the attendance example.
