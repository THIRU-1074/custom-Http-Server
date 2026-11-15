# Design principles

How Jolt stays flexible and easy to use without growing. Read with [CONSTITUTION.md](../CONSTITUTION.md);
the constitution is the hard rules, this is the design contract behind them.

## Ease of use

1. **Sensible defaults.** `Jolt.create().listen(port)` must give a safe, working server. Limits,
   timeouts and error handling are on out of the box.
2. **Configuration is optional and in one place.** Anything tunable goes through `JoltConfig`, with a
   default. No required setup, no config files, no annotations.
3. **Small, Express-shaped API.** A newcomer should guess the method name. A new public method needs an
   ADR (constitution rule 3 and 6); prefer extending an existing one.
4. **Risky features are opt-in.** CORS, route listing, static files and MCP are off until enabled.

## Flexibility

5. **SPIs only where users must swap behaviour.** Today that means `JsonMapper`, codecs and error
   mappers. Everything else stays a concrete class. A new SPI needs a real second implementation
   in mind, not a hypothetical one.
6. **No reflection beyond record binding.** No classpath scanning, no proxies, no runtime bytecode.
7. **Zero runtime dependencies in `jolt-core`.** Integrations live in optional modules.

## Performance

8. **The request hot path stays lean.** No allocation or locking is added to it without a benchmark
   showing no meaningful change (`benchmarks/guard/perf-guard.py` is a smoke check, not proof).
9. **Measure before claiming.** Numbers in docs come from a recorded run with machine and JDK
   noted; see `benchmarks/RESULTS.md`.

## Token-small docs

10. **Every feature's `llms.txt` entry is one line.** The file stays under 8000 characters
    (`LlmsTxtBudgetTest`) and 2100 tokens (`token-guard.py`). If it is full, tighten text first. Raising
    a limit needs an ADR (see ADR 0003, the one-time raise for the v0.3 feature set).
11. **The todo app stays small.** The Jolt benchmark app may not grow more than 5% in tokens without
    a reviewed baseline refresh.

## Checklist for a new feature

- Does it work with no configuration?
- Is the config an optional `JoltConfig` field?
- Is it one line in `llms.txt`?
- Does it touch the hot path? If so, where is the benchmark?
- Is it a test, not just code?
