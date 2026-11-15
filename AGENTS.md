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

## Worktrees and workspace hygiene

Worktrees pile up fast with parallel agents and make the editor slow (file watching, search, git status). Clean up as you go:

- Before removing a worktree, confirm its work is safe: `git status --short` is clean (ignoring `target/`) and the branch is pushed or merged, or its commits are reported to the user. Never force-remove a worktree holding unpushed, unreported commits.
- When a task or job ends, remove its worktree: `git worktree remove <path>` (add `--force` only after the check above, to drop untracked `target/` output), then `git worktree prune`.
- Delete the local branch once it is merged: `git branch -d <branch>`. Delete the remote branch only when the user asks.
- Stop servers and other background processes you started, and delete scratch clones and temp directories.
- Keep at most the worktrees for jobs that are still running. If more than 3 exist when you start, list them with `git worktree list` and tell the user which look finished.
- Create worktrees only under `.claude/worktrees/` (git-ignored). Never inside `src/`.
- Hide them from the editor: add `.claude/worktrees` to `files.watcherExclude` and `search.exclude` in `.vscode/settings.json`.
