package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class LifecycleTest {

    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("in.jolt");
    private final Handler handler =
            new Handler() {
                @Override
                public void publish(LogRecord r) {
                    logs.add(r);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };

    @BeforeEach
    void attach() {
        logger.addHandler(handler);
    }

    @AfterEach
    void detach() {
        logger.removeHandler(handler);
    }

    @Test
    void startHooksRunInOrderBeforeAnyConnectionIsServed() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        App app = Jolt.create();
        app.onStart(() -> events.add("a")).onStart(() -> events.add("b"));
        app.get("/", (req, res) -> res.text("ok"));
        try (Server server = app.listen(0)) {
            assertEquals(List.of("a", "b"), events);
            try (RawClient c = new RawClient(server.port())) {
                c.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n");
                assertEquals(200, c.read().status());
            }
        }
    }

    @Test
    void stopHooksRunOnceInReverseOrderAndCloseIsIdempotent() {
        List<String> events = new ArrayList<>();
        App app = Jolt.create();
        app.onStop(() -> events.add("1")).onStop(() -> events.add("2"));
        app.onStop(() -> events.add("3"));
        Server server = app.listen(0);
        assertTrue(events.isEmpty());
        server.close();
        server.close();
        assertEquals(List.of("3", "2", "1"), events);
    }

    @Test
    void aFailingStopHookDoesNotSkipTheRest() {
        List<String> events = new ArrayList<>();
        App app = Jolt.create();
        app.onStop(() -> events.add("first"));
        app.onStop(
                () -> {
                    throw new IllegalStateException("boom");
                });
        app.onStop(() -> events.add("last"));
        app.listen(0).close();
        assertEquals(List.of("last", "first"), events);
        assertTrue(
                logs.stream()
                        .anyMatch(
                                r ->
                                        r.getLevel() == Level.WARNING
                                                && r.getThrown() instanceof IllegalStateException));
    }

    @Test
    void shutdownHookRunsTheStopHooks() throws Exception {
        List<String> events = new ArrayList<>();
        App app = Jolt.create();
        app.onStop(() -> events.add("stopped"));
        Server server = app.listen(0).closeOnShutdown();
        int port = server.port();
        Thread hook = server.shutdownHook();
        assertNotNull(hook);
        hook.run();
        assertEquals(List.of("stopped"), events);
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port));
        hook.run();
        server.close();
        assertEquals(List.of("stopped"), events);
    }

    @Test
    void stopHooksRunAfterInFlightRequestsFinish() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean handlerDone = new AtomicBoolean();
        AtomicBoolean doneWhenStopped = new AtomicBoolean();
        App app = Jolt.create();
        app.onStop(() -> doneWhenStopped.set(handlerDone.get()));
        app.get(
                "/slow",
                (req, res) -> {
                    inside.countDown();
                    release.await();
                    handlerDone.set(true);
                    res.text("done");
                });
        Server server = app.listen(0);
        try (RawClient c = new RawClient(server.port())) {
            c.send("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            Thread closer = new Thread(server::close);
            closer.start();
            Thread.sleep(200);
            assertFalse(doneWhenStopped.get());
            release.countDown();
            assertEquals(200, c.read().status());
            closer.join(10_000);
        }
        assertTrue(doneWhenStopped.get());
    }

    @Test
    void hooksRegisteredAfterListenAreRejected() {
        App app = Jolt.create();
        try (Server ignored = app.listen(0)) {
            IllegalStateException e =
                    assertThrows(IllegalStateException.class, () -> app.onStart(() -> {}));
            assertTrue(e.getMessage().contains("listen"), e.getMessage());
            assertThrows(IllegalStateException.class, () -> app.onStop(() -> {}));
        }
    }

    @Test
    void failedStartAbortsListenClosesThePortAndStillStops() throws Exception {
        List<String> events = new ArrayList<>();
        App app = Jolt.create();
        app.onStart(() -> events.add("pool-open"));
        app.onStart(
                () -> {
                    throw new IllegalArgumentException("db down");
                });
        app.onStart(() -> events.add("never"));
        app.onStop(() -> events.add("pool-close"));
        int port;
        try (var probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> app.listen(port));
        assertTrue(e.getMessage().contains("onStart"), e.getMessage());
        assertEquals("db down", e.getCause().getMessage());
        assertEquals(List.of("pool-open", "pool-close"), events);
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port));
        try (var rebind = new java.net.ServerSocket(port)) {
            assertEquals(port, rebind.getLocalPort());
        }
    }
}
