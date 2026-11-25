# v0.1.0 versus current: design comparison

Files used for the "v0.1.0 versus 0.4.2" section of [../RESULTS.md](../RESULTS.md).

| File | Purpose |
|---|---|
| `BenchApp-v0.1.0.java` | One-route app (`GET /ping`) for the v0.1.0 tag. Copy it to `jolt-http-server/src/main/java/in/jolt/BenchApp.java` in a checkout of `v0.1.0` (it uses package-private API) and run `in.jolt.BenchApp`. |
| `PingApp.java` | The same route on the current jolt-core. Pass `hold` to raise `keepAliveTimeout` to 120 s so idle connections are kept open. |
| `../load/Idle.java` | Opens N keep-alive connections and leaves them idle, then times 15 probe requests. |
| `stats.ps1` | Prints thread count, working set and established connections of the process listening on a port (PowerShell, Windows). |

```
java benchmarks/load/Idle.java 8080 800          # while a server is listening on 8080
powershell -File benchmarks/compare/stats.ps1 8080
java benchmarks/load/Load.java http://localhost:8080/ping 50 30
```

Run each server with `-Xmx448m`, one at a time.
