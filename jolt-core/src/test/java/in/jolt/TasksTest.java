package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TasksTest {

    private static boolean taskThreadsAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("jolt-task") && t.isAlive());
    }

    private static void await(BooleanSupplier c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "condition not reached");
            Thread.sleep(5);
        }
    }

    @Test
    void everyRepeatsOnAVirtualThreadAfterListen() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean virtual = new AtomicBoolean();
        App app = Jolt.create();
        app.every(
                Duration.ofMillis(10),
                () -> {
                    virtual.set(Thread.currentThread().isVirtual());
                    runs.incrementAndGet();
                });
        Thread.sleep(50);
        assertEquals(0, runs.get(), "nothing runs before listen");
        try (Server server = app.listen(0)) {
            await(() -> runs.get() >= 3);
        }
        assertTrue(virtual.get());
    }

    @Test
    void afterRunsOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        App app = Jolt.create();
        app.after(Duration.ofMillis(10), runs::incrementAndGet);
        try (Server server = app.listen(0)) {
            await(() -> runs.get() == 1);
            Thread.sleep(80);
            assertEquals(1, runs.get());
        }
    }

    @Test
    void closeStopsTasksAndLeavesNoThreads() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        App app = Jolt.create();
        app.every(Duration.ofMillis(5), runs::incrementAndGet);
        Server server = app.listen(0);
        await(() -> runs.get() >= 2);
        server.close();
        int after = runs.get();
        Thread.sleep(60);
        assertEquals(after, runs.get());
        assertFalse(taskThreadsAlive());
    }

    @Test
    void closeInterruptsARunningTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        App app = Jolt.create();
        app.after(
                Duration.ofMillis(1),
                () -> {
                    started.countDown();
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                    }
                });
        Server server = app.listen(0);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        server.close();
        assertTrue(interrupted.await(10, TimeUnit.SECONDS));
        assertFalse(taskThreadsAlive());
    }

    @Test
    void aThrowingTaskKeepsItsSchedule() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        App app = Jolt.create();
        app.every(
                "flaky",
                Duration.ofMillis(10),
                () -> {
                    runs.incrementAndGet();
                    throw new IllegalStateException("boom");
                });
        try (Server server = app.listen(0)) {
            await(() -> runs.get() >= 3);
        }
    }

    @Test
    void overlappingRunsAreSkippedNotStacked() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        App app = Jolt.create();
        app.exposeMetrics();
        app.every(
                "slow",
                Duration.ofMillis(10),
                () -> {
                    max.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    runs.incrementAndGet();
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        concurrent.decrementAndGet();
                    }
                });
        try (Server server = app.listen(0)) {
            await(() -> runs.get() >= 2);
        }
        assertEquals(1, max.get());
        String metrics = app.metrics().render();
        assertTrue(metrics.contains("jolt_task_skipped_total{task=\"slow\"} "), metrics);
        assertFalse(metrics.contains("jolt_task_skipped_total{task=\"slow\"} 0\n"), metrics);
    }

    @Test
    void metricsCountRunsAndFailuresPerTask() throws Exception {
        AtomicInteger good = new AtomicInteger();
        AtomicInteger bad = new AtomicInteger();
        App app = Jolt.create();
        app.every("good", Duration.ofMillis(10), good::incrementAndGet);
        app.every(
                "bad",
                Duration.ofMillis(10),
                () -> {
                    bad.incrementAndGet();
                    throw new RuntimeException("x");
                });
        app.exposeMetrics();
        try (Server server = app.listen(0)) {
            await(() -> good.get() >= 2 && bad.get() >= 2);
        }
        String m = app.metrics().render();
        assertTrue(m.contains("jolt_task_runs_total{task=\"good\"} "), m);
        assertTrue(m.contains("jolt_task_runs_total{task=\"bad\"} "), m);
        assertTrue(m.contains("jolt_task_failures_total{task=\"good\"} 0\n"), m);
        assertFalse(m.contains("jolt_task_failures_total{task=\"bad\"} 0\n"), m);
    }

    @Test
    void metricsHaveNoTaskSeriesWithoutTasks() {
        App app = Jolt.create();
        app.exposeMetrics();
        assertFalse(app.metrics().render().contains("jolt_task"));
    }

    @Test
    void validatesArgumentsAndNames() {
        App app = Jolt.create();
        assertThrows(IllegalArgumentException.class, () -> app.every(Duration.ZERO, () -> {}));
        assertThrows(
                IllegalArgumentException.class, () -> app.every(Duration.ofSeconds(-1), () -> {}));
        assertThrows(
                IllegalArgumentException.class, () -> app.after(Duration.ofSeconds(-1), () -> {}));
        assertThrows(NullPointerException.class, () -> app.every(Duration.ofSeconds(1), null));
        app.every("a", Duration.ofSeconds(1), () -> {});
        assertThrows(
                IllegalArgumentException.class,
                () -> app.every("a", Duration.ofSeconds(1), () -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () -> app.every("bad name!", Duration.ofSeconds(1), () -> {}));
    }

    @Test
    void registeringAfterListenIsRejected() throws Exception {
        App app = Jolt.create();
        try (Server server = app.listen(0)) {
            assertThrows(
                    IllegalStateException.class, () -> app.every(Duration.ofSeconds(1), () -> {}));
        }
    }
}
