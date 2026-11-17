# ADR 0009: tooling and ecosystem modules

## Status
Accepted.

## Context
Issues #33 to #36 add a dev loop, scaffolding, a database helper and Maven Central preparation. None may add a dependency to `jolt-core` (Constitution 1) or enable anything by default (Constitution 2).

## Decision
- `jolt-dev`: `Dev.run` starts the app in a child JVM and restarts it when watched directories change. `DevErrors.page()` is a middleware that renders the exception and stack, and exists only when the app installs it.
- `jolt-archetype`: a Maven archetype that generates the starter. It points at the published JitPack coordinates through a `joltVersion` property, default `v0.2.0`, which is bumped on each release.
- `jolt-db`: a bounded JDBC pool (8 connections, 5 s acquire timeout by default) with `Db.query`, `queryOne`, `update` and `inTransaction`. It depends on `java.sql` only, so there is no string-concatenating API, only bound parameters. sqlite-jdbc is test scope.
- Maven Central: an inactive `release` profile in the root pom, plus `docs/RELEASING.md` for the manual steps. The groupId stays `in.jolt` until the owner chooses a namespace.
- Each feature gets one line in `llms-extras.txt`; `llms.txt` is untouched.

## Consequences
- Core, its tests and its token budget are unchanged.
- A JVM restart is about 0.4 s for a trivial app, so the dev loop is a restart, not hot reload. State in memory is lost on each change.
- The archetype duplicates the starter sources; keep them in step when the starter changes.
