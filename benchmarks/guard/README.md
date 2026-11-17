# Guards

Automated checks that keep Jolt's two selling points while features are added: no large
performance regression, and token-small docs and code.

| Guard | What it checks | Runs in CI |
|---|---|---|
| `LlmsTxtBudgetTest` (jolt-core) | `llms.txt` is at most 8000 characters and identical to the bundled copy | yes (`mvn verify`) |
| `token-guard.py` | cl100k_base tokens of `llms.txt` (limit 2100), `llms-extras.txt` (limit 3200) and the Jolt todo app (limit +5% over `token-baseline.json`) | yes |
| `perf-guard.py` | median req/s and latency of the todo app against `baseline.json` | **no**, local only |

## perf-guard.py

```
python benchmarks/guard/perf-guard.py --baseline benchmarks/guard/baseline.json
```

Builds jolt-core, compiles the todo app from `benchmarks/tokens/jolt` with `GuardApp.java` (picks the
port), starts it on a free loopback port, warms up for 8 s, then runs `benchmarks/load/Load.java`
(50 clients, 20 s) three times. It writes the median req/s and median mean latency to `last-run.json`
and exits 1 if req/s drops more than 15% or latency rises more than 25% against the baseline. The app
process is always stopped in a `finally` block.

This is a **smoke guard, not a benchmark**. Client and server share one machine and run-to-run
variance is large, so the thresholds are generous and only catch big regressions. Use
`benchmarks/RESULTS.md` methodology for real numbers. It is not run in CI because shared runners are
too noisy.

### Baselines are per machine

CPU differs across machines, so `baseline.json` stores a machine id (OS, CPU name, logical CPUs) and
the JDK. If the id does not match the current machine, the script prints a warning, records
`last-run.json` and exits 0 without comparing.

Refresh the baseline on your machine, on an otherwise idle system, after an intended change:

```
python benchmarks/guard/perf-guard.py --write-baseline
```

## token-guard.py

```
python benchmarks/guard/token-guard.py
python benchmarks/guard/token-guard.py --write-baseline   # after an intended, reviewed increase
```

Needs `pip install tiktoken`. Without it the script falls back to chars/4 and labels the output as an
estimate; baselines are only compared with the same tokenizer.
