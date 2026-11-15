# Load script

JDK-only closed-loop load generator: 50 virtual-thread clients hit one URL for 30 seconds,
then print throughput and mean latency of successful (status < 400) requests.

```
java benchmarks/load/Load.java [url] [concurrency] [seconds] ["Header: value"]
java benchmarks/load/Load.java http://localhost:8080/ 50 30
```

Start the app under test first. Use the same machine and the same route as the v0.1 baseline so
the numbers are comparable.

| Version | Throughput | Mean latency |
|---|---|---|
| v0.1 (Postman, 50 VU) | 27.45 req/s | 295 ms |

Postman numbers include its own client overhead, so treat the comparison as indicative.
