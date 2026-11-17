package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class HubTest {

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    private static Socket openSse(Server server, String path, String extraHeaders)
            throws IOException {
        Socket s = new Socket("127.0.0.1", server.port());
        s.setSoTimeout(5000);
        s.getOutputStream()
                .write(
                        ("GET " + path + " HTTP/1.1\r\nHost: t\r\n" + extraHeaders + "\r\n")
                                .getBytes(StandardCharsets.ISO_8859_1));
        return s;
    }

    /** Closes the clients and publishes until the hub notices, so the server stops at once. */
    private static void leave(Hub<?> hub, String topic, Socket... clients) throws Exception {
        for (Socket c : clients) {
            c.close();
        }
        @SuppressWarnings("unchecked")
        Hub<Object> h = (Hub<Object>) hub;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hub.subscribers(topic) > 0) {
            assertTrue(System.nanoTime() < deadline, "closed client still subscribed");
            h.publish(topic, "ping");
            Thread.sleep(10);
        }
    }

    private static String readUntil(Socket s, String needle) throws IOException {
        InputStream in = s.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[1024];
        while (sb.indexOf(needle) < 0) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        assertTrue(sb.indexOf(needle) >= 0, "missing " + needle + " in " + sb);
        return sb.toString();
    }

    @Test
    void publishReachesEveryLiveSubscriberOfTheTopicInOrder() throws Exception {
        Hub<String> hub = Hub.create();
        List<List<String>> got = new ArrayList<>();
        List<Long> ids = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<String> mine = new CopyOnWriteArrayList<>();
            got.add(mine);
            hub.subscribe(
                    "show-1",
                    null,
                    (id, m) -> {
                        mine.add(m);
                        if (got.get(0) == mine) {
                            ids.add(id);
                        }
                    });
        }
        List<String> other = new CopyOnWriteArrayList<>();
        hub.subscribe("show-2", null, (id, m) -> other.add(m));
        assertEquals(3, hub.subscribers("show-1"));
        assertEquals(4, hub.subscribers());

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            expected.add("seat-" + i);
            assertEquals(3, hub.publish("show-1", "seat-" + i));
        }
        for (List<String> mine : got) {
            await(() -> mine.size() == 50, "50 messages");
            assertEquals(expected, mine);
        }
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1), "ids increase");
        }
        assertTrue(other.isEmpty());
        assertEquals(0, hub.publish("nobody", "x"));
    }

    @Test
    void sseSubscribersReceiveEventsWithIdsOverLoopback() throws Exception {
        Hub<Object> hub = Hub.create();
        App app = Jolt.create();
        app.get("/shows/:id/seats", (req, res) -> hub.sse(req.param("id"), req, res));
        try (Server server = app.listen(0);
                Socket a = openSse(server, "/shows/1/seats", "");
                Socket b = openSse(server, "/shows/1/seats", "")) {
            await(() -> hub.subscribers("1") == 2, "two SSE subscribers");
            hub.publish("1", "A1");
            hub.publish("1", Map.of("seat", "A2"));
            for (Socket s : List.of(a, b)) {
                String body = readUntil(s, "data: {\"seat\":\"A2\"}\n\n");
                assertTrue(body.contains("text/event-stream"), body);
                assertTrue(body.matches("(?s).*id: \\d+\ndata: A1\n\n.*"), body);
            }
            leave(hub, "1", a, b);
        }
    }

    @Test
    void lastEventIdReplaysWhatTheClientMissed() throws Exception {
        Hub<String> hub = Hub.create(16, 100, 8);
        App app = Jolt.create();
        app.get("/s", (req, res) -> hub.sse("t", req, res));
        List<Long> ids = new CopyOnWriteArrayList<>();
        try (Hub<String>.Subscription probe = hub.subscribe("t", null, (id, m) -> ids.add(id))) {
            hub.publish("t", "a");
            hub.publish("t", "b");
            hub.publish("t", "c");
            await(() -> ids.size() == 3, "probe");
        }
        try (Server server = app.listen(0);
                Socket s = openSse(server, "/s", "Last-Event-ID: " + ids.get(0) + "\r\n")) {
            String body = readUntil(s, "data: c\n\n");
            assertFalse(body.contains("data: a\n"), body);
            assertTrue(body.contains("id: " + ids.get(1) + "\ndata: b\n\n"), body);
            hub.publish("t", "d");
            readUntil(s, "data: d\n\n");
            leave(hub, "t", s);
        }

        List<String> fresh = new CopyOnWriteArrayList<>();
        hub.subscribe("t", "not-a-number", (id, m) -> fresh.add(m));
        hub.publish("t", "e");
        await(() -> fresh.contains("e"), "live message");
        assertEquals(List.of("e"), fresh);
    }

    @Test
    void aClosedClientIsRemoved() throws Exception {
        Hub<String> hub = Hub.create();
        App app = Jolt.create();
        app.get("/s", (req, res) -> hub.sse("t", req, res));
        try (Server server = app.listen(0)) {
            Socket s = openSse(server, "/s", "");
            await(() -> hub.subscribers("t") == 1, "subscriber");
            leave(hub, "t", s);
        }

        Hub<String>.Subscription sub = hub.subscribe("u", null, (id, m) -> {});
        assertTrue(sub.isOpen());
        sub.close();
        assertFalse(sub.isOpen());
        assertEquals(0, hub.subscribers());
        assertEquals(0, hub.publish("u", "x"));

        Hub<String>.Subscription failing =
                hub.subscribe(
                        "v",
                        null,
                        (id, m) -> {
                            throw new IOException("gone");
                        });
        hub.publish("v", "x");
        await(() -> !failing.isOpen(), "failing sink removed");
        assertEquals(0, hub.subscribers("v"));
    }

    @Test
    void aSlowClientIsDroppedWithoutBlockingOthers() throws Exception {
        Hub<Integer> hub = Hub.create(4, 100, 0);
        App app = Jolt.create().exposeMetrics();
        hub.metrics(app.metrics(), "seats");
        CountDownLatch release = new CountDownLatch(1);
        Hub<Integer>.Subscription slow = hub.subscribe("t", null, (id, m) -> release.await());
        List<Integer> fast = new CopyOnWriteArrayList<>();
        hub.subscribe("t", null, (id, m) -> fast.add(m));

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            hub.publish("t", i);
            if (i % 4 == 3) {
                int want = i + 1;
                await(() -> fast.size() == want, "fast subscriber keeps up");
            }
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        assertEquals(200, fast.size());
        assertFalse(slow.isOpen());
        assertEquals(1, hub.subscribers("t"));
        release.countDown();

        String scrape = JoltTest.of(app).get("/_jolt/metrics").expect(200).text();
        assertTrue(scrape.contains("seats_subscribers 1\n"), scrape);
        assertTrue(scrape.contains("seats_dropped_total 1\n"), scrape);
    }

    @Test
    void theSubscriberCapAnswers503() throws Exception {
        Hub<String> hub = Hub.create(4, 2, 0);
        hub.subscribe("a", null, (id, m) -> {});
        hub.subscribe("b", null, (id, m) -> {});
        HttpException e =
                assertThrows(HttpException.class, () -> hub.subscribe("a", null, (id, m) -> {}));
        assertEquals(503, e.status());
        assertEquals(2, hub.subscribers());

        App app = Jolt.create();
        app.get("/s", (req, res) -> hub.sse("a", req, res));
        try (Server server = app.listen(0);
                Socket s = openSse(server, "/s", "")) {
            assertTrue(readUntil(s, "\r\n").startsWith("HTTP/1.1 503"));
        }
    }

    @Test
    void rejectsBadSettingsAndArguments() {
        assertThrows(IllegalArgumentException.class, () -> Hub.create(0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> Hub.create(4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Hub.create(4, 10, 5));
        assertThrows(IllegalArgumentException.class, () -> Hub.create(4, 10, -1));
        Hub<String> hub = Hub.create();
        assertThrows(NullPointerException.class, () -> hub.subscribe(null, null, (i, m) -> {}));
        assertThrows(NullPointerException.class, () -> hub.subscribe("t", null, null));
        assertThrows(NullPointerException.class, () -> hub.publish("t", null));
    }

    @Test
    void webSocketSessionsSubscribeAndLeaveOnClose() throws Exception {
        Hub<String> hub = Hub.create();
        App app = Jolt.create();
        app.ws("/shows/:id/live", hub.ws(req -> req.param("id")));
        try (HttpClient client = HttpClient.newHttpClient();
                Server server = app.listen(0)) {
            URI uri = URI.create("ws://127.0.0.1:" + server.port() + "/shows/7/live");
            WebSocketTest.Events e1 = new WebSocketTest.Events();
            WebSocketTest.Events e2 = new WebSocketTest.Events();
            WebSocket w1 =
                    client.newWebSocketBuilder().buildAsync(uri, e1).get(5, TimeUnit.SECONDS);
            WebSocket w2 =
                    client.newWebSocketBuilder().buildAsync(uri, e2).get(5, TimeUnit.SECONDS);
            await(() -> hub.subscribers("7") == 2, "two WebSocket subscribers");
            hub.publish("7", "B4");
            hub.publish("7", "B5");
            for (WebSocketTest.Events e : List.of(e1, e2)) {
                assertEquals("B4", e.next());
                assertEquals("B5", e.next());
            }
            w1.sendClose(1000, "bye").get(5, TimeUnit.SECONDS);
            await(() -> hub.subscribers("7") == 1, "closed session unsubscribed");
            w2.abort();
        }
    }
}
