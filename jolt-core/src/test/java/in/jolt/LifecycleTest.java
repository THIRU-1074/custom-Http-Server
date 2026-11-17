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
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicBoolean doneWhenStopped = new AtomicBoolean();
        App app = Jolt.create();
        app.onStop(
                () -> {
                    doneWhenStopped.set(handlerDone.get());
                    stopped.set(true);
                });
        app.get(
                "/slow",
                (req, res) -> {
                    inside.countDown();
                    release.await();
                    handlerDone.set(true);
                    res.text("done");
                });
        Server server = app.listen(0);
        int port = server.port();
        Thread closer = new Thread(server::close);
        try (RawClient c = new RawClient(port)) {
            c.send("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            closer.start();
            while (accepts(port)) {
                Thread.onSpinWait();
            }
            assertFalse(stopped.get());
            release.countDown();
            assertEquals(200, c.read().status());
            closer.join(10_000);
        }
        assertFalse(closer.isAlive());
        assertTrue(doneWhenStopped.get());
    }

    private static boolean accepts(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static int freePort() throws Exception {
        try (var probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void assertPortFree(int port) throws Exception {
        try (var rebind = new java.net.ServerSocket(port)) {
            assertEquals(port, rebind.getLocalPort());
        }
    }

    @Test
    void anErrorInAStopHookIsRethrownAfterTheRestRanButNotByTheShutdownHook() throws Exception {
        List<String> events = new ArrayList<>();
        App app = Jolt.create();
        app.onStop(() -> events.add("first"));
        app.onStop(
                () -> {
                    throw new NoClassDefFoundError("gone");
                });
        app.onStop(() -> events.add("last"));
        Server server = app.listen(0);
        int port = server.port();
        assertThrows(NoClassDefFoundError.class, server::close);
        assertEquals(List.of("last", "first"), events);
        assertPortFree(port);
        server.close();

        List<String> second = new ArrayList<>();
        App other = Jolt.create();
        other.onStop(() -> second.add("a"));
        other.onStop(
                () -> {
                    throw new AssertionError("boom");
                });
        Server s2 = other.listen(0).closeOnShutdown();
        s2.shutdownHook().run();
        assertEquals(List.of("a"), second);
        assertTrue(logs.stream().anyMatch(r -> r.getThrown() instanceof AssertionError));
    }

    @Test
    void anErrorInAStartHookLeavesThePortFreeAndStopsOnce() throws Exception {
        List<String> events = new ArrayList<>();
        int port = freePort();
        App app = Jolt.create();
        app.onStop(() -> events.add("stop"));
        app.onStart(
                () -> {
                    throw new AssertionError("start");
                });
        assertThrows(AssertionError.class, () -> app.listen(port));
        assertEquals(List.of("stop"), events);
        assertPortFree(port);
    }

    @Test
    void aConstructionFailureLeavesThePortFreeAndStopsOnce() throws Exception {
        JoltConfig d = JoltConfig.defaults();
        JoltConfig bad =
                new JoltConfig(
                        d.maxConnections(),
                        d.maxHeaderBytes(),
                        d.maxBodyBytes(),
                        d.maxRequestsPerConnection(),
                        d.readTimeout(),
                        d.keepAliveTimeout(),
                        d.jsonMapper(),
                        d.maxRequestLineBytes(),
                        d.maxHeaderCount(),
                        d.secureCookies(),
                        d.maxStaticFileBytes(),
                        d.bindAddress(),
                        d.maxPartBytes(),
                        d.maxParts(),
                        List.of("not-a-network"),
                        d.problemJson(),
                        d.tls(),
                        d.streamIdleTimeout(),
                        d.streamMaxDuration(),
                        d.wsMaxFrameBytes(),
                        d.wsMaxMessageBytes());
        List<String> events = new ArrayList<>();
        int port = freePort();
        App app = Jolt.create(bad);
        app.onStop(() -> events.add("stop"));
        assertThrows(IllegalArgumentException.class, () -> app.listen(port));
        assertEquals(List.of("stop"), events);
        assertPortFree(port);
    }

    @Test
    void aFailedListenLeavesRegistrationOpen() throws Exception {
        List<String> events = new ArrayList<>();
        try (var busy = new java.net.ServerSocket(0)) {
            App app = Jolt.create();
            app.onStop(() -> events.add("stop"));
            assertThrows(java.io.UncheckedIOException.class, () -> app.listen(busy.getLocalPort()));
            assertEquals(List.of("stop"), events);
            app.onStart(() -> events.add("start")).onStop(() -> events.add("stop2"));
            app.listen(0).close();
            assertEquals(List.of("stop", "start", "stop2", "stop"), events);
        }
    }

    @Test
    void startHooksRunBeforeThePortIsBound() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        int port = freePort();
        App app = Jolt.create();
        app.onStart(
                () -> {
                    inside.countDown();
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        Thread lister = new Thread(() -> app.listen(port).close());
        lister.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        assertFalse(accepts(port));
        gate.countDown();
        lister.join(10_000);
        assertFalse(lister.isAlive());
    }

    @Test
    void lifecyclePairsStopOnlyWhenTheirStartCompleted() {
        List<String> events = new ArrayList<>();
        App app = Jolt.create();
        app.lifecycle(() -> events.add("a+"), () -> events.add("a-"));
        app.lifecycle(
                () -> {
                    throw new IllegalStateException("b");
                },
                () -> events.add("b-"));
        app.lifecycle(() -> events.add("c+"), () -> events.add("c-"));
        app.onStop(() -> events.add("plain"));
        assertThrows(IllegalStateException.class, () -> app.listen(0));
        assertEquals(List.of("a+", "plain", "a-"), events);
    }

    @Test
    void lifecyclePairsRunBothSidesOnASuccessfulServer() {
        List<String> events = new ArrayList<>();
        App app = Jolt.create();
        app.lifecycle(() -> events.add("a+"), () -> events.add("a-"));
        app.lifecycle(() -> events.add("b+"), () -> events.add("b-"));
        app.listen(0).close();
        assertEquals(List.of("a+", "b+", "b-", "a-"), events);
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
