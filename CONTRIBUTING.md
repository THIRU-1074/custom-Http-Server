# Contributing

Read [CONSTITUTION.md](CONSTITUTION.md) first.

Design contract: [docs/PRINCIPLES.md](docs/PRINCIPLES.md). Size and speed guards: [benchmarks/guard/README.md](benchmarks/guard/README.md).

## Setup

Requires JDK 21 and Maven. Activate the repo hooks once per clone:

```
git config core.hooksPath .githooks
```

The hooks check the commit message, lint staged Java sources in every module (`jolt-*` and `examples/*`), run `spotless:check` when Java is staged, block direct pushes to `main`, and run `mvn verify` before a push. Maven must be on the `PATH`; the hooks fail without it.

## Branches

Branch from `main` as `<type>/<short-kebab-description>`, for example `feat/route-trie` or `fix/put-method-map`. Never commit directly to `main`.

## Commits

Conventional Commits: `type(scope): subject`.

- Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`, `build`, `ci`.
- Scope is optional but preferred (`core`, `auth`, `mcp`, `examples`).
- Subject is imperative, lower case, no trailing period, at most 72 characters.
- No emojis.
- One logical change per commit. A bug fix and its failing test go in the same commit.

## Pull requests

Before opening a PR:

- [ ] `mvn -B verify` passes locally.
- [ ] Behavior changes have tests.
- [ ] No new runtime dependency in `jolt-core`.
- [ ] Public API additions have an ADR in `docs/adr/`.
- [ ] No `System.out`, `printStackTrace`, narration comments or untracked `TODO`s in library code.
- [ ] Commits are atomic and follow Conventional Commits.

## Review-then-push flow

1. Work on a branch and commit in small steps.
2. Review `git log --oneline` and `git diff main...HEAD` yourself.
3. Push the branch and open a PR. CI must pass.
4. Merge after review. Branch protection on `main` should require CI and a review.

AI tools follow [AGENTS.md](AGENTS.md) and do not commit or push unless asked.
