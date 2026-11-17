package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StreamingTest {

    private static final String GET = "GET /s HTTP/1.1\r\nHost: t\r\n\r\n";

    private static Socket open(Server server) throws IOException {
        Socket s = new Socket("127.0.0.1", server.port());
        s.setSoTimeout(5000);
        s.getOutputStream().write(GET.getBytes(StandardCharsets.ISO_8859_1));
        return s;
    }

    private static String readUntil(InputStream in, String marker) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.toString(StandardCharsets.ISO_8859_1).endsWith(marker)) {
                break;
            }
        }
        return b.toString(StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void eventsArriveInOrderWithEventStreamHeaders() throws Exception {
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    EventStream s = res.sse();
                    s.event("tick", "1");
                    s.event("tick", "line one\nline two");
                    s.comment("beat");
                    s.retry(Duration.ofSeconds(3));
                    s.event("done");
                    s.close();
                });
        try (Server server = app.listen(0);
                Socket c = open(server)) {
            String raw = readAll(c.getInputStream());
            String head = raw.substring(0, raw.indexOf("\r\n\r\n"));
            assertTrue(head.startsWith("HTTP/1.1 200 OK"));
            assertTrue(head.contains("Content-Type: text/event-stream"));
            assertTrue(head.contains("Cache-Control: no-cache"));
            assertTrue(head.contains("Transfer-Encoding: chunked"));
            assertTrue(head.contains("Connection: close"));
            String body = unchunk(raw.substring(raw.indexOf("\r\n\r\n") + 4));
            assertEquals(
                    "event: tick\ndata: 1\n\n"
                            + "event: tick\ndata: line one\ndata: line two\n\n"
                            + ": beat\n\n"
                            + "retry: 3000\n\n"
                            + "data: done\n\n",
                    body);
        }
    }

    @Test
    void chunkedFramingIsExact() throws Exception {
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    ResponseStream s = res.stream();
                    s.write("hello");
                    s.write(new byte[0]);
                    s.write("0123456789abcdef");
                });
        try (Server server = app.listen(0);
                Socket c = open(server)) {
            String raw = readAll(c.getInputStream());
            assertTrue(
                    raw.endsWith("\r\n\r\n5\r\nhello\r\n10\r\n0123456789abcdef\r\n0\r\n\r\n"), raw);
        }
    }

    @Test
    void eventsAreNotBuffered() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    EventStream s = res.sse();
                    s.event("first", "now");
                    release.await(5, TimeUnit.SECONDS);
                    s.event("second", "later");
                    s.close();
                });
        try (Server server = app.listen(0);
                Socket c = open(server)) {
            String early = readUntil(c.getInputStream(), "data: now\n\n\r\n");
            assertTrue(early.contains("event: first"), early);
            release.countDown();
            assertTrue(readAll(c.getInputStream()).contains("event: second"));
        }
    }

    @Test
    void disconnectSurfacesAsIoExceptionInTheHandler() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    EventStream s = res.sse();
                    try {
                        while (true) {
                            s.event("x", "y");
                            Thread.sleep(5);
                        }
                    } catch (IOException e) {
                        seen.set(e);
                        failed.countDown();
                    }
                });
        try (Server server = app.listen(0)) {
            Socket c = open(server);
            readUntil(c.getInputStream(), "data: y\n\n\r\n");
            c.setSoLinger(true, 0);
            c.close();
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertTrue(seen.get() instanceof IOException);
        }
    }

    @Test
    void idleStreamIsClosedAndItsHandlerInterrupted() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        App app =
                Jolt.create(
                        JoltConfig.defaults()
                                .streamTimeouts(Duration.ofMillis(300), Duration.ofHours(1)));
        app.get(
                "/s",
                (req, res) -> {
                    res.sse().event("only", "one");
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        ended.countDown();
                    }
                });
        try (Server server = app.listen(0);
                Socket c = open(server)) {
            long start = System.nanoTime();
            readAll(c.getInputStream());
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 4);
        }
    }

    @Test
    void totalLimitEndsAStreamThatKeepsWriting() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        App app =
                Jolt.create(
                        JoltConfig.defaults()
                                .streamTimeouts(Duration.ofSeconds(30), Duration.ofMillis(400)));
        app.get(
                "/s",
                (req, res) -> {
                    EventStream s = res.sse();
                    try {
                        while (true) {
                            s.comment("beat");
                            Thread.sleep(20);
                        }
                    } catch (IOException | InterruptedException e) {
                        ended.countDown();
                    }
                });
        try (Server server = app.listen(0);
                Socket c = open(server)) {
            assertTrue(ended.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidUseIsRejected() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> name = new AtomicReference<>();
        AtomicReference<Throwable> afterClose = new AtomicReference<>();
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    EventStream s = res.sse();
                    try {
                        s.event("bad\nname", "x");
                    } catch (IllegalArgumentException e) {
                        name.set(e);
                    }
                    ResponseStream raw = res.stream();
                    raw.close();
                    try {
                        raw.write("late");
                    } catch (IOException e) {
                        afterClose.set(e);
                    }
                    done.countDown();
                });
        try (Server server = app.listen(0);
                Socket c = open(server)) {
            readAll(c.getInputStream());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(name.get() instanceof IllegalArgumentException);
            assertTrue(afterClose.get() instanceof IOException);
        }
        assertThrows(
                IllegalStateException.class,
                () -> new Response(null).stream(),
                "no live connection");
        assertThrows(
                IllegalArgumentException.class,
                () -> JoltConfig.defaults().streamTimeouts(Duration.ZERO, Duration.ZERO));
    }

    private static String unchunk(String raw) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int eol = raw.indexOf("\r\n", i);
            int size = Integer.parseInt(raw.substring(i, eol), 16);
            if (size == 0) {
                return out.toString();
            }
            out.append(raw, eol + 2, eol + 2 + size);
            i = eol + 2 + size + 2;
        }
    }
}
