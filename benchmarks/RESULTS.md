# Jolt server performance

Throughput, latency and resource use of Jolt **0.4.2**, measured against two earlier points:

1. **v0.1.0**, the original server with an unbounded thread per connection: the large difference is here
   (next section).
2. **0.2.0**, the earlier benchmark run, kept unchanged in [RESULTS-0.2.0.md](RESULTS-0.2.0.md): the
   difference there is small, because 0.2.0 had already moved to virtual threads
   ([release-to-release](#release-to-release-020-versus-042)).

Spring Boot, Javalin and Express are measured the same way as reference points.

## v0.1.0 versus 0.4.2

Same machine, same JDK, `-Xmx448m` on both servers, one trivial route (`GET /ping`), one server at a time.
v0.1.0 was built from the `v0.1.0` tag with a one-route app; 0.4.2 uses the current `jolt-core`.

### Steady load: 50 clients, 30 s

| | v0.1.0 | 0.4.2 | Difference |
|---|---:|---:|---:|
| Throughput | **65.7 req/s** (65.6, 65.9) | **22,743 req/s** (19,728, 25,865, 22,637) | **346x higher** |
| Mean latency | 698 ms (704, 693) | 2.2 ms (2.5, 1.9, 2.2) | **317x lower** |
| Failed requests | 8.8% and 10.1% of requests | 0 of 2,047,129 | |
| Threads after the run | 28 | 39 | |

```
throughput (log scale, each block is a factor of 2)
v0.1.0  ||||||                        66 req/s
0.4.2   |||||||||||||||               22,743 req/s
```

### 800 idle keep-alive connections held open

| | v0.1.0 | 0.4.2 (keep-alive 120 s) | Difference |
|---|---:|---:|---:|
| Server threads | **2,443** | **29** | **84x fewer** |
| Threads per connection | about 3 | none (virtual threads, idle ones parked in one `Selector`) | |
| Memory growth (working set) | 43 to 240 MB (+197 MB) | 57 to 66 MB (+9 MB) | **22x less growth** |
| Latency of a new request while the 800 sit idle (median of 15) | **513 ms** | **1 ms** | **513x lower** |
| Connections held | 801 | 800 | |

```
server threads with 800 idle connections (each block is about 40 threads)
v0.1.0  ||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||| 2,443
0.4.2   | 29
```

With the default 0.4.2 configuration (`keepAliveTimeout` 5 s) the server closes idle connections after 5 s, so
no connections were established when sampled; threads 29, memory 67 MB, probe latency 2 ms. That is the bound
doing its job, and it is why the table above raises the timeout to hold the connections open.

### Why the gap is this large

Both causes are visible in the v0.1.0 source (`ClientConnection.java`):

- **Three platform threads per connection.** One for the connection, one `ClientResponder`, and a new
  `RequestHandler` thread per request, with no limit. 800 connections became 2,443 threads and 197 MB of growth.
  0.4.2 serves requests on virtual threads, caps open connections (`maxConnections`, default 1000) and parks idle
  keep-alive connections in a single `Selector` poller.
- **Sleep polling.** v0.1.0 waits for a request with `Thread.sleep(250)` loops and for the response queue with
  `Thread.sleep(500)` loops. The 513 ms probe latency and the 698 ms steady-load latency match those sleeps. This
  part of the gap comes from the polling design and not from the thread count alone.
- **Connection reuse.** v0.1.0 keeps a connection open only when the request carries exactly
  `Connection: keep-alive`. The JDK `HttpClient` used by `Load.java` does not send that header, so v0.1.0 closed
  the connection after every response in the steady test, and the failed requests are consistent with that churn.
  A client that sends the header would likely see different numbers; that case was not measured.

The idle test shows the unbounded-thread cost in isolation, since its probe latency is the only part touched by
the sleeps. The steady test mixes both causes.

## Release to release: 0.2.0 versus 0.4.2

| | Jolt 0.2.0 (old) | Jolt 0.4.2 (new) |
|---|---|---|
| Measured | 2026-11-16 | 2026-11-25 |
| Mean throughput | 21,206 req/s | **21,512 req/s** |
| Mean latency | 2.47 ms | **2.33 ms** |
| Run-to-run spread | 16,178 to 25,604 req/s | **20,697 to 22,196 req/s** |
| Errors | 0 | 0 |

**Reading:** Jolt's average moved by about +1.4%, which is inside noise. What changed is steadiness:
the spread between the best and worst run shrank from about 9,400 req/s to about 1,500 req/s.
Spring Boot and Javalin were faster in this session, so Jolt now sits behind both. See
[Reading the results](#reading-the-results) before drawing conclusions.

---

## Setup

| | |
|---|---|
| Machine | AMD Ryzen 5 5625U (6 cores / 12 threads), 13.8 GB RAM, Windows 11 |
| JDK / Node | Oracle OpenJDK 24.0.1 (modules compiled with `--release 21`), Node v22.16.0 |
| Jolt | `in.jolt:jolt-core:0.4.2`, installed from this repo into the local Maven repository |
| Server JVMs | `-Xmx448m` for Jolt, Javalin and Spring Boot |
| Client | `benchmarks/load/Load.java`: 50 virtual-thread clients, JDK `HttpClient`, HTTP/1.1 |
| Request | `GET /todos` with `Authorization: Bearer secret`, empty list |
| Duration | 30 s per run, 3 consecutive runs per app on one JVM, apps started fresh and run one at a time |

Client and server share one machine, so the client competes with the server for CPU.

```
java benchmarks/load/Load.java http://localhost:8080/todos 50 30 "Authorization: Bearer secret"
```

---

## Throughput, averaged over runs

Each block is about 1,000 req/s.

```
                      old (0.2.0 run)                      new (0.4.2 run)
Jolt        |||||||||||||||||||||  21.2k        |||||||||||||||||||||| 21.5k
Spring Boot |||||||||||||||||| 17.7k            ||||||||||||||||||||||||| 24.1k
Javalin     ||||||||||||||||  15.8k              ||||||||||||||||||||||||| 24.8k
Express     |||| 3.4k                            |||| 4.0k
```

```mermaid
xychart-beta
    title "Mean throughput (req/s), old run vs new run"
    x-axis ["Jolt", "Spring Boot", "Javalin", "Express"]
    y-axis "req/s" 0 --> 28000
    bar [21206, 17678, 15817, 3405]
    bar [21512, 24079, 24804, 4024]
```

First bar of each pair: old run (Jolt 0.2.0 session). Second bar: new run (Jolt 0.4.2 session).

## Old versus new, side by side

| Framework | Old mean req/s | New mean req/s | Change | Old mean latency | New mean latency |
|---|---:|---:|---:|---:|---:|
| **Jolt** | 21,206 | **21,512** | +1.4% | 2.47 ms | **2.33 ms** |
| Spring Boot | 17,678 | 24,079 | +36.2% | 2.87 ms | 2.07 ms |
| Javalin | 15,817 | 24,804 | +56.8% | 3.15 ms | 2.03 ms |
| Express | 3,405 | 4,024 | +18.2% | 14.7 ms | 12.4 ms |

Competitor rows moved a lot between sessions while Jolt barely did. That points at session conditions
(background load, thermal state, JIT luck) as much as at the code, so the old and new competitor rows
should not be read as framework improvements.

## Jolt against the others in the new run

| Framework | Mean req/s | Versus Jolt | Mean latency |
|---|---:|---:|---:|
| Javalin | 24,804 | Jolt is 13.3% lower | 2.03 ms |
| Spring Boot | 24,079 | Jolt is 10.7% lower | 2.07 ms |
| **Jolt** | **21,512** | baseline | **2.33 ms** |
| Express | 4,024 | Jolt is 5.3x higher | 12.4 ms |

In the old run Jolt led Spring Boot by 20% and Javalin by 34%, but with a 9,400 req/s spread in Jolt's own
runs, that lead was not reliable either.

---

## Every run

### Jolt 0.4.2 (new)

| Run | Requests | req/s | Mean latency | Failed |
|---:|---:|---:|---:|---:|
| 1 | 649,392 | 21,644 | 2.3 ms | 0 |
| 2 | 620,979 | 20,697 | 2.4 ms | 0 |
| 3 | 665,957 | 22,196 | 2.3 ms | 0 |

### Reference frameworks (new)

| App | Run | req/s | Mean latency | Failed |
|---|---:|---:|---:|---:|
| Javalin | 1 | 24,124 | 2.1 ms | 0 |
| Javalin | 2 | 26,225 | 1.9 ms | 0 |
| Javalin | 3 | 24,063 | 2.1 ms | 0 |
| Spring Boot | 1 | 24,090 | 2.1 ms | 0 |
| Spring Boot | 2 | 23,729 | 2.1 ms | 0 |
| Spring Boot | 3 | 24,419 | 2.0 ms | 0 |
| Express | 1 | 3,883 | 12.9 ms | 0 |
| Express | 2 | 4,086 | 12.2 ms | 0 |
| Express | 3 | 4,103 | 12.2 ms | 0 |

### Old run (Jolt 0.2.0), for comparison

| App | Run | req/s | Mean latency |
|---|---:|---:|---:|
| Jolt | 1 | 16,178 | 3.1 ms |
| Jolt | 2 | 25,604 | 2.0 ms |
| Jolt | 3 | 21,836 | 2.3 ms |
| Spring Boot | 1 | 20,657 | 2.4 ms |
| Spring Boot | 2 | 16,357 | 3.1 ms |
| Spring Boot | 3 | 16,021 | 3.1 ms |
| Javalin | 1 | 16,111 | 3.1 ms |
| Javalin | 2 | 15,523 | 3.2 ms |
| Express | 1 | 3,405 | 14.7 ms |

---

## History

| Version | Tool | req/s | Mean latency |
|---|---|---:|---:|
| v0.1 | Postman, 50 VU, 1 min | 27.45 | 295 ms |
| v0.1.0 | `Load.java`, 50 clients, 30 s, `GET /ping` | 65.7 | 698 ms |
| 0.2.0 | `Load.java`, 50 clients, 30 s | 21,206 | 2.47 ms |
| 0.4.2 | `Load.java`, 50 clients, 30 s | 21,512 | 2.33 ms |

The Postman figure used a different tool, route and client; the `Load.java` row for v0.1.0 re-measures it with
the same tool as the later rows. Part of the v0.1.0 gap is the sleep polling described above, not only the
threading model.

---

## Reading the results

- **Spring Boot's first launch (discarded run).** Spring Boot's first session in this round had a third run that took 1,481 s
  instead of 30 s, with 47 failures and 458 req/s. The machine appears to have stalled or slept, so the run is not a
  measurement of the server. Spring Boot was relaunched from scratch and the three runs above are from that relaunch.
  Its first two runs before the stall were 20,975 and 23,533 req/s, in line with the relaunch.
- **Jolt is steadier, not demonstrably faster.** The mean difference (+1.4%) is smaller than the spread
  between Jolt's own runs, so the throughput change is not significant. The narrower spread is the clearer signal.
- **Jolt trails Javalin and Spring Boot by 11 to 13% in this session.** That gap is larger than Jolt's new run-to-run
  spread (about 3.5% either side of the mean), so it is likely real for this setup. The earlier 0.2.0 session showed
  the opposite order, so the ranking depends on the session. Repeat on a quiet machine before quoting it.
- **No cause is claimed.** This run does not isolate which change between 0.2.0 and 0.4.2 affected the numbers.
  The 0.4.x line changed connection handling (virtual threads, a keep-alive poller), but no profiling was done here.

## Not measured

- One route (`GET /todos`, empty list): no body parsing, no writes, no larger payloads.
- No latency percentiles (only the mean), no memory or CPU use, no TLS, no HTTP keep-alive tuning comparison.
- Client and server on one machine; no warm-up control beyond three consecutive runs.
- The v0.1.0 comparison uses one route and one idle-connection count (800, below the 1000 connection limit).
  Beyond the limit, overload behaviour, slow clients and a client that sends `Connection: keep-alive` were not run.
  Each v0.1.0 and idle measurement is a single launch.
- Token counts and behaviour checks are unchanged from [RESULTS-0.2.0.md](RESULTS-0.2.0.md) and were not repeated here.

## Reproduce

```
mvn -DskipTests install                        # Jolt 0.4.2 into the local repository
cd benchmarks/tokens/jolt    && mvn package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd benchmarks/tokens/javalin && mvn package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd benchmarks/tokens/spring  && mvn package
cd benchmarks/tokens/express && npm install

java -Xmx448m -cp "target/classes;$(cat target/cp.txt)" todo.TodoApp     # from the app's directory
java benchmarks/load/Load.java http://localhost:8080/todos 50 30 "Authorization: Bearer secret"
```

Run each app on its own, three times in a row, and stop it before starting the next.
