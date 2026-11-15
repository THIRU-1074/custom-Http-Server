# Agent rules

Rules for AI coding tools working in this repo. See [CONSTITUTION.md](CONSTITUTION.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

- No comments that restate the code. No `// Step 1` narration.
- No emojis in code, docs or commits.
- Javadoc on public API only, at most 3 lines unless the behavior is subtle.
- No `System.out` or `printStackTrace` in library code; use `System.Logger`.
- No speculative abstractions. No new files or dependencies without need.
- `jolt-core` takes no runtime dependencies.
- Keep diffs minimal; touch only what the task needs.
- Every behavior change comes with a test.
- Commits follow Conventional Commits, one logical change each.
- Do not commit or push unless asked.
