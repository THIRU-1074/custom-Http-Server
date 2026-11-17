# ADR 0014: Scheduled and background tasks

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Seat holds must expire and stale data must be purged on a timer, and apps that hand-roll executors forget to stop them (#58).

## Decision
- `App.every(Duration, Runnable)` and `App.after(Duration, Runnable)`, each with a `String name` overload for a stable metrics label. Without a name the task is called `task-N`. `every` needs a positive period and first runs one period after `listen`; `after` takes a non-negative delay and runs once. Names match `[A-Za-z0-9_.:-]{1,64}` and are unique per app, else `IllegalArgumentException`.
- Tasks ride the lifecycle hooks (ADR 0012): each registers a start and stop pair, so nothing runs before `listen`, registering after `listen` throws `IllegalStateException`, and `Server.close()` stops them after in-flight requests drain.
- Each task has a virtual-thread ticker that fires on a fixed rate and runs the body on its own virtual thread. A tick that finds the previous run still going is skipped and counted, never queued. A throwing body is logged at WARNING on `in.jolt` and the schedule continues. On close the ticker and a running body are interrupted and joined for up to 5 s each.
- With `exposeMetrics()` the output gains `jolt_task_runs_total`, `jolt_task_failures_total` and `jolt_task_skipped_total`, labelled by `task`. The section is empty when no task is registered. `Metrics` gains one internal `section` hook for this.
- Tests use short real intervals; there is no clock abstraction, because the tick logic is a monotonic-time loop with nothing to fake.

## Consequences
- jolt-core stays dependency-free (`java.lang` and `java.util.concurrent` only). Apps without tasks pay nothing.
- Core grows by four `App` methods; the scheduler lives in `in.jolt.internal.task`. One `llms-extras.txt` line under Lifecycle.
- No cron expressions, no persistence and no distributed locking: with several instances every instance runs every task.
- A task that ignores interruption can outlive `close()` by the 5 s join limit; it is then left to finish on its virtual thread.
