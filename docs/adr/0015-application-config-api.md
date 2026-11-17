# ADR 0015: Application configuration loader

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Apps need their own settings (database URL, secrets, hold minutes) from the environment or a properties file with fail-fast errors; `JoltConfig.fromEnv` only covers Jolt's own settings (#60).

## Decision
- `Config.load()` reads `application.properties` from the classpath root and the working directory (the directory wins), then `application-<profile>.properties` from the same places. The profile is `JOLT_PROFILE`, else `-Djolt.profile`; it must match `[A-Za-z0-9_-]{1,32}`, so it cannot name another path. `Config.load(Map env, Path dir)` is the testable form.
- A key `db.url` is read from the environment as `db.url`, then `DB_URL`, then the files. The environment always wins. A blank value counts as missing.
- Typed getters `string`, `integer`, `duration` and `bool`, each as `get(key)` (required) and `get(key, default)`. `duration` takes `500ms`, `30s`, `5m`, `2h`, `1d` or ISO-8601; `bool` takes only `true` or `false`. A missing required key or a malformed value, even when a default exists, throws `ConfigException` (unchecked) with a one-line message naming the key. The message never contains the value, since the key may hold a secret.
- `toString()` lists the keys from the files with their effective values and masks any key containing `secret`, `password` or `token` (case-insensitive) as `****`. Environment-only keys are not listed, so the output never dumps the process environment.
- Properties files are parsed with `java.util.Properties` as UTF-8; no interpolation, no nesting, no YAML.

## Consequences
- jolt-core stays dependency-free. Core grows by `Config` and `ConfigException`, at one `llms-extras.txt` line under Lifecycle.
- A secret that is only in the environment is not shown by `toString()` at all; one in a file is masked.
- `Config` is immutable and snapshots the environment at load, so later environment changes are not seen.
