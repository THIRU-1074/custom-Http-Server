package in.jolt.internal.task;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Scheduled tasks: a virtual-thread ticker per task, one run at a time, started and stopped
 * together.
 */
public final class Tasks {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final long STOP_WAIT_MS = 5_000;

    private final Map<String, Task> tasks = new ConcurrentSkipListMap<>();

    /** Registers a task. {@code period} is {@code null} for a one-shot. */
    public Task add(String name, Duration delay, Duration period, Runnable body) {
        Objects.requireNonNull(body, "task");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Task name must match " + NAME.pattern());
        }
        Task task = new Task(name, delay, period, body);
        if (tasks.putIfAbsent(name, task) != null) {
            throw new IllegalArgumentException("Duplicate task name " + name);
        }
        return task;
    }

    /** A task name that is not taken yet. */
    public String nextName() {
        int n = tasks.size() + 1;
        while (tasks.containsKey("task-" + n)) {
            n++;
        }
        return "task-" + n;
    }

    /** Per-task counters in Prometheus text format; empty when there are no tasks. */
    public String render() {
        if (tasks.isEmpty()) {
            return "";
        }
        List<Task> all = List.copyOf(tasks.values());
        StringBuilder sb = new StringBuilder();
        series(sb, "jolt_task_runs_total", "Task runs started.", all, t -> t.runs.sum());
        series(sb, "jolt_task_failures_total", "Task runs that threw.", all, t -> t.failures.sum());
        series(
                sb,
                "jolt_task_skipped_total",
                "Ticks skipped because the previous run was still going.",
                all,
                t -> t.skipped.sum());
        return sb.toString();
    }

    private static void series(
            StringBuilder sb,
            String metric,
            String help,
            List<Task> all,
            java.util.function.ToLongFunction<Task> value) {
        sb.append("# HELP ").append(metric).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(metric).append(" counter\n");
        for (Task t : all) {
            sb.append(metric)
                    .append("{task=\"")
                    .append(t.name)
                    .append("\"} ")
                    .append(value.applyAsLong(t))
                    .append('\n');
        }
    }

    public static final class Task {
        private final String name;
        private final long delayNanos;
        private final long periodNanos;
        private final Runnable body;
        private final LongAdder runs = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder skipped = new LongAdder();
        private final AtomicBoolean running = new AtomicBoolean();
        private volatile boolean stopped = true;
        private volatile Thread ticker;
        private volatile Thread current;

        private Task(String name, Duration delay, Duration period, Runnable body) {
            this.name = name;
            this.delayNanos = delay.toNanos();
            this.periodNanos = period == null ? 0 : period.toNanos();
            this.body = body;
        }

        public synchronized void start() {
            if (!stopped) {
                return;
            }
            stopped = false;
            Thread t = Thread.ofVirtual().name("jolt-task-" + name).unstarted(this::tick);
            ticker = t;
            t.start();
        }

        public void stop() {
            Thread t;
            synchronized (this) {
                stopped = true;
                t = ticker;
                ticker = null;
            }
            interruptAndJoin(t);
            interruptAndJoin(current);
        }

        private static void interruptAndJoin(Thread t) {
            if (t == null) {
                return;
            }
            t.interrupt();
            try {
                t.join(Duration.ofMillis(STOP_WAIT_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void tick() {
            long next = System.nanoTime() + delayNanos;
            try {
                while (!stopped) {
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(Duration.ofNanos(wait));
                    }
                    if (stopped) {
                        return;
                    }
                    fire();
                    if (periodNanos == 0) {
                        return;
                    }
                    next = Math.max(next + periodNanos, System.nanoTime());
                }
            } catch (InterruptedException e) {
                // stop() interrupts the ticker
            }
        }

        private void fire() {
            if (!running.compareAndSet(false, true)) {
                skipped.increment();
                return;
            }
            runs.increment();
            Thread t =
                    Thread.ofVirtual()
                            .name("jolt-task-" + name + "-run")
                            .unstarted(
                                    () -> {
                                        try {
                                            body.run();
                                        } catch (Throwable e) {
                                            failures.increment();
                                            if (!stopped) {
                                                LOG.log(
                                                        System.Logger.Level.WARNING,
                                                        "Task " + name + " failed",
                                                        e);
                                            }
                                        } finally {
                                            running.set(false);
                                        }
                                    });
            current = t;
            t.start();
        }
    }
}
