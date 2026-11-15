# ADR 0001: Java 21, multi-module Maven, zero-dependency core

Status: accepted

## Context

Jolt must become an importable library. At v0.1.0 it is a single module that mixes the framework, two demo apps and SQLite/JWT dependencies, and it spawns several platform threads per connection.

## Decision

1. **Java 21 LTS.** Virtual threads let us run one thread per connection with plain blocking code, removing the sleep-polling and response queue. Records and pattern matching keep the API small.
2. **Multi-module Maven.** `jolt-core` is the library; `jolt-auth` and `jolt-mcp` are optional modules; demo apps live under `examples/`.
3. **Zero runtime dependencies in `jolt-core`.** Users who import Jolt pull in nothing transitively. Optional features that need libraries live in separate modules. JSON is handled by a minimal built-in implementation behind a `JsonMapper` SPI, so Jackson can be plugged in.

## Consequences

- Users need JDK 21 or newer.
- We maintain a small JSON parser/writer and HTTP parser ourselves, and must test them thoroughly.
- Examples carry their own dependencies (for example SQLite) without affecting the library.
- Adding a dependency to `jolt-core` requires a new ADR superseding this one.
