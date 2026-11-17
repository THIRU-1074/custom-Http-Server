package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TasksTest {

    private final AtomicLong clock = new AtomicLong();
    private final CountDownLatch release = new CountDownLatch(1);

    private JoltTest app(Tasks tasks) {
        App app = Jolt.create();
        app.module(tasks);
        app.post(
                "/slow",
                (req, res) ->
                        tasks.start(
                                req,
                                res,
                                () -> {
                                    release.await();
                                    return Map.of("answer", 42);
                                }));
        app.post("/fast", (req, res) -> tasks.start(req, res, () -> Map.of("answer", 1)));
        app.post(
                "/boom",
                (req, res) ->
                        tasks.start(
                                req,
                                res,
                                () -> {
                                    throw new IllegalStateException("secret detail");
                                }));
        return JoltTest.of(app);
    }

    private static String id(JoltTest.Result r) {
        return String.valueOf(r.jsonField("id"));
    }

    @Test
    void startAnswers202WithLocationAndPollsToDone() {
        JoltTest t = app(Tasks.inMemory());
        JoltTest.Result started = t.post("/slow").expect(202);
        String id = id(started);
        assertEquals(32, id.length());
        started.header("Location", "/tasks/" + id)
                .jsonEquals("status", "running")
                .jsonEquals("href", "/tasks/" + id);
        t.get("/tasks/" + id).expect(200).jsonEquals("status", "running");
        release.countDown();
        t.get("/tasks/" + id + "?wait=10")
                .expect(200)
                .jsonEquals("status", "done")
                .jsonEquals("result.answer", 42);
    }

    @Test
    void longPollReturnsAsSoonAsTheTaskEnds() {
        JoltTest t = app(Tasks.inMemory());
        String id = id(t.post("/fast").expect(202));
        long begin = System.nanoTime();
        t.get("/tasks/" + id + "?wait=30").expect(200).jsonEquals("status", "done");
        assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(10));
    }

    @Test
    void failureHidesTheExceptionMessage() {
        JoltTest t = app(Tasks.inMemory());
        String id = id(t.post("/boom").expect(202));
        JoltTest.Result r =
                t.get("/tasks/" + id + "?wait=10").expect(200).jsonEquals("status", "failed");
        r.jsonEquals("error", "Task failed");
        assertTrue(!r.text().contains("secret detail"));
    }

    @Test
    void deleteCancelsARunningTaskAndInterruptsIt() {
        JoltTest t = app(Tasks.inMemory());
        String id = id(t.post("/slow").expect(202));
        t.delete("/tasks/" + id).expect(204);
        t.get("/tasks/" + id).expect(200).jsonEquals("status", "cancelled");
        t.delete("/tasks/" + id).expect(204);
        t.get("/tasks/" + id).expect(404);
    }

    @Test
    void unknownIdAndBadWaitAreRejected() {
        JoltTest t = app(Tasks.inMemory());
        t.get("/tasks/nope").expect(404);
        String id = id(t.post("/fast").expect(202));
        t.get("/tasks/" + id + "?wait=abc").expect(400);
        t.get("/tasks/" + id + "?wait=-1").expect(400);
    }

    @Test
    void ownerIsolatesTasks() {
        Tasks tasks = Tasks.inMemory().owner(req -> req.header("X-User"));
        JoltTest t = app(tasks);
        String id = id(t.post("/fast").header("X-User", "ann").expect(202));
        t.get("/tasks/" + id).header("X-User", "bob").expect(404);
        t.delete("/tasks/" + id).header("X-User", "bob").expect(404);
        t.get("/tasks/" + id + "?wait=10").header("X-User", "ann").expect(200);
        t.post("/fast").expect(401);
    }

    @Test
    void fullCapacityAnswers503WithRetryAfter() {
        JoltTest t = app(Tasks.inMemory(1, 1, Duration.ofMinutes(1), clock::get));
        t.post("/slow").expect(202);
        t.post("/slow").expect(503).header("Retry-After", "1");
        release.countDown();
    }

    @Test
    void finishedTasksExpireAfterTtl() {
        JoltTest t = app(Tasks.inMemory(4, 4, Duration.ofSeconds(60), clock::get));
        String id = id(t.post("/fast").expect(202));
        t.get("/tasks/" + id + "?wait=10").expect(200).jsonEquals("status", "done");
        clock.addAndGet(Duration.ofSeconds(59).toNanos());
        t.get("/tasks/" + id).expect(200);
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        t.get("/tasks/" + id).expect(404);
    }

    @Test
    void fullStoreEvictsTheOldestFinishedTask() {
        JoltTest t = app(Tasks.inMemory(1, 1, Duration.ofMinutes(1), clock::get));
        String first = id(t.post("/fast").expect(202));
        t.get("/tasks/" + first + "?wait=10").expect(200).jsonEquals("status", "done");
        String second = id(t.post("/fast").expect(202));
        t.get("/tasks/" + first).expect(404);
        t.get("/tasks/" + second + "?wait=10").expect(200);
    }

    @Test
    void customPathMovesRoutesAndLocation() {
        JoltTest t = app(Tasks.inMemory().path("/jobs"));
        JoltTest.Result started = t.post("/fast").expect(202);
        String id = id(started);
        started.header("Location", "/jobs/" + id);
        t.get("/jobs/" + id + "?wait=10").expect(200).jsonEquals("status", "done");
        t.get("/tasks/" + id).expect(404);
    }

    @Test
    void toolAfterModuleTagsThePollRoute() {
        App app = Jolt.create();
        app.module(Tasks.inMemory()).tool("Check a task");
        Route tagged =
                app.routes().stream().filter(r -> r.tool() != null).findFirst().orElseThrow();
        assertEquals("GET", tagged.method());
        assertEquals("/tasks/:id", tagged.path());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> Tasks.inMemory(0, 1, Duration.ofMinutes(1)));
        assertThrows(
                IllegalArgumentException.class, () -> Tasks.inMemory(2, 1, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> Tasks.inMemory(1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Tasks.inMemory().path("jobs"));
        assertThrows(IllegalArgumentException.class, () -> Tasks.inMemory().path("/jobs/"));
    }
}
