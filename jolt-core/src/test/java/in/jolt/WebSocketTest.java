package in.jolt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebSocketTest {

    private static final WebSocketHandler ECHO =
            new WebSocketHandler() {
                @Override
                public void onText(WebSocketSession s, String text) throws Exception {
                    if (text.equals("bye")) {
                        s.close(4001, "server says bye");
                    } else {
                        s.send(text);
                    }
                }

                @Override
                public void onBinary(WebSocketSession s, byte[] data) throws Exception {
                    s.send(data);
                }
            };

    static final class Events implements WebSocket.Listener {
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream bin = new ByteArrayOutputStream();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                queue.add(text.toString());
                text.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            bin.writeBytes(b);
            if (last) {
                queue.add(bin.toByteArray());
                bin.reset();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer m) {
            queue.add("ping");
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer m) {
            byte[] b = new byte[m.remaining()];
            m.get(b);
            queue.add("pong:" + new String(b, StandardCharsets.UTF_8));
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            queue.add("close:" + code + ":" + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            queue.add("error:" + error);
        }

        Object next() throws InterruptedException {
            Object o = queue.poll(5, TimeUnit.SECONDS);
            assertTrue(o != null, "timed out waiting for a WebSocket event");
            return o;
        }
    }

    private static WebSocket connect(HttpClient client, Server server, String path, Events events)
            throws Exception {
        return client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + path), events)
                .get(5, TimeUnit.SECONDS);
    }

    private static App echoApp(JoltConfig config) {
        App app = Jolt.create(config);
        app.ws("/echo", ECHO);
        return app;
    }

    @Test
    void echoesTextAndBinary() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient();
                Server server = echoApp(JoltConfig.defaults()).listen(0)) {
            Events ev = new Events();
            WebSocket ws = connect(client, server, "/echo", ev);
            ws.sendText("hello", true).get(5, TimeUnit.SECONDS);
            assertEquals("hello", ev.next());
            ws.sendText("héllo 世界", true).get(5, TimeUnit.SECONDS);
            assertEquals("héllo 世界", ev.next());
            ws.sendBinary(ByteBuffer.wrap(new byte[] {1, 2, 3, (byte) 255}), true)
                    .get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] {1, 2, 3, (byte) 255}, (byte[]) ev.next());
            ws.sendClose(1000, "done").get(5, TimeUnit.SECONDS);
            assertEquals("close:1000:done", ev.next());
        }
    }

    @Test
    void pingGetsPongAndServerCanPing() throws Exception {
        WebSocketHandler h =
                new WebSocketHandler() {
                    @Override
                    public void onText(WebSocketSession s, String text) throws Exception {
                        s.ping();
                    }
                };
        App app = Jolt.create();
        app.ws("/p", h);
        try (HttpClient client = HttpClient.newHttpClient();
                Server server = app.listen(0)) {
            Events ev = new Events();
            WebSocket ws = connect(client, server, "/p", ev);
            ws.sendPing(ByteBuffer.wrap("hi".getBytes(StandardCharsets.UTF_8)))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("pong:hi", ev.next());
            ws.sendText("go", true).get(5, TimeUnit.SECONDS);
            assertEquals("ping", ev.next());
            ws.abort();
        }
    }

    @Test
    void idleConnectionIsPingedByTheServer() throws Exception {
        App app =
                echoApp(
                        JoltConfig.defaults()
                                .streamTimeouts(Duration.ofMillis(200), Duration.ofHours(1)));
        try (HttpClient client = HttpClient.newHttpClient();
                Server server = app.listen(0)) {
            Events ev = new Events();
            WebSocket ws = connect(client, server, "/echo", ev);
            assertEquals("ping", ev.next());
            ws.abort();
        }
    }

    @Test
    void oversizeMessageClosesWith1009() throws Exception {
        App app = echoApp(JoltConfig.defaults().wsLimits(1024, 4096));
        try (HttpClient client = HttpClient.newHttpClient();
                Server server = app.listen(0)) {
            Events ev = new Events();
            WebSocket ws = connect(client, server, "/echo", ev);
            ws.sendText("x".repeat(5000), true);
            assertTrue(((String) ev.next()).startsWith("close:1009"));
        }
    }

    @Test
    void fragmentsAreJoinedAndLimitedAsAMessage() throws Exception {
        App app = echoApp(JoltConfig.defaults().wsLimits(1024, 100));
        try (Server server = app.listen(0);
                Raw c = Raw.connect(server.port(), "/echo", null)) {
            c.frame(0x1, false, "he".getBytes());
            c.frame(0x9, true, "mid".getBytes());
            c.frame(0x0, false, "ll".getBytes());
            c.frame(0x0, true, "o".getBytes());
            Raw.Frame pong = c.read();
            assertEquals(0xA, pong.op());
            assertEquals("mid", new String(pong.payload()));
            Raw.Frame echo = c.read();
            assertEquals(0x1, echo.op());
            assertEquals("hello", new String(echo.payload()));
            c.frame(0x1, false, new byte[60]);
            c.frame(0x0, true, new byte[60]);
            assertEquals(1009, c.readCloseCode());
        }
    }

    @Test
    void protocolViolationsAreClosedWithTheRightCode() throws Exception {
        try (Server server = echoApp(JoltConfig.defaults()).listen(0)) {
            try (Raw c = Raw.connect(server.port(), "/echo", null)) {
                c.frame(0x1, true, new byte[] {(byte) 0xC3, 0x28});
                assertEquals(1007, c.readCloseCode());
            }
            try (Raw c = Raw.connect(server.port(), "/echo", null)) {
                c.unmaskedFrame(0x1, "hi".getBytes());
                assertEquals(1002, c.readCloseCode());
            }
            try (Raw c = Raw.connect(server.port(), "/echo", null)) {
                c.frame(0x0, true, "orphan".getBytes());
                assertEquals(1002, c.readCloseCode());
            }
            try (Raw c = Raw.connect(server.port(), "/echo", null)) {
                c.frame(0x3, true, new byte[0]);
                assertEquals(1002, c.readCloseCode());
            }
            try (Raw c = Raw.connect(server.port(), "/echo", null)) {
                c.frame(0x9, false, new byte[0]);
                assertEquals(1002, c.readCloseCode());
            }
        }
    }

    @Test
    void closeHandshakeEchoesAndNotifiesTheHandler() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger code = new AtomicInteger();
        WebSocketHandler h =
                new WebSocketHandler() {
                    @Override
                    public void onClose(WebSocketSession s, int c, String reason) {
                        code.set(c);
                        closed.countDown();
                    }
                };
        App app = Jolt.create();
        app.ws("/c", h);
        try (Server server = app.listen(0);
                Raw c = Raw.connect(server.port(), "/c", null)) {
            c.frame(0x8, true, new byte[] {0x03, (byte) 0xE9});
            assertEquals(1001, c.readCloseCode());
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertEquals(1001, code.get());
        }
    }

    @Test
    void serverInitiatedCloseReachesTheClient() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient();
                Server server = echoApp(JoltConfig.defaults()).listen(0)) {
            Events ev = new Events();
            WebSocket ws = connect(client, server, "/echo", ev);
            ws.sendText("bye", true).get(5, TimeUnit.SECONDS);
            assertEquals("close:4001:server says bye", ev.next());
            ws.sendClose(1000, "ok");
        }
    }

    @Test
    void badHandshakesAreRefused() throws Exception {
        try (Server server = echoApp(JoltConfig.defaults()).listen(0)) {
            assertEquals(
                    426,
                    Raw.status(server.port(), "GET /echo HTTP/1.1\r\nHost: t\r\n\r\n").status());
            String base = "GET /echo HTTP/1.1\r\nHost: t\r\nUpgrade: websocket\r\n";
            String key = "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
            assertEquals(
                    400,
                    Raw.status(server.port(), base + key + "Sec-WebSocket-Version: 13\r\n\r\n")
                            .status());
            String conn = base + "Connection: Upgrade\r\n";
            Raw.Head wrongVersion =
                    Raw.status(server.port(), conn + key + "Sec-WebSocket-Version: 8\r\n\r\n");
            assertEquals(426, wrongVersion.status());
            assertTrue(wrongVersion.text().contains("Sec-WebSocket-Version: 13"));
            assertEquals(
                    400,
                    Raw.status(
                                    server.port(),
                                    conn
                                            + "Sec-WebSocket-Key: short\r\nSec-WebSocket-Version: 13\r\n\r\n")
                            .status());
            assertEquals(
                    400,
                    Raw.status(server.port(), conn + "Sec-WebSocket-Version: 13\r\n\r\n").status());
            assertEquals(
                    405,
                    Raw.status(
                                    server.port(),
                                    "POST /echo HTTP/1.1\r\nHost: t\r\nContent-Length: 0\r\n\r\n")
                            .status());
        }
    }

    @Test
    void originMustMatchTheHostOrTheAllowlist() throws Exception {
        App app = Jolt.create();
        app.ws("/same", ECHO);
        app.ws("/listed", ECHO).origins("https://a.example");
        try (Server server = app.listen(0)) {
            String host = "127.0.0.1:" + server.port();
            assertEquals(101, Raw.upgrade(server.port(), "/same", host, null).status());
            assertEquals(101, Raw.upgrade(server.port(), "/same", host, "http://" + host).status());
            assertEquals(
                    403,
                    Raw.upgrade(server.port(), "/same", host, "https://evil.example").status());
            assertEquals(403, Raw.upgrade(server.port(), "/same", host, "null").status());
            assertEquals(
                    101, Raw.upgrade(server.port(), "/listed", host, "https://a.example").status());
            assertEquals(
                    403, Raw.upgrade(server.port(), "/listed", host, "http://" + host).status());
            assertEquals(101, Raw.upgrade(server.port(), "/listed", host, null).status());
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> JoltConfig.defaults().wsLimits(0, 10));
        assertThrows(IllegalArgumentException.class, () -> JoltConfig.defaults().wsLimits(10, 0));
    }

    @Test
    void handlerExceptionClosesWith1011() throws Exception {
        WebSocketHandler h =
                WebSocketHandler.text(
                        (s, t) -> {
                            throw new IllegalStateException("boom");
                        });
        App app = Jolt.create();
        app.ws("/x", h);
        try (Server server = app.listen(0);
                Raw c = Raw.connect(server.port(), "/x", null)) {
            c.frame(0x1, true, "go".getBytes());
            assertEquals(1011, c.readCloseCode());
        }
    }

    static final class Raw implements AutoCloseable {
        record Head(int status, String text) {}

        record Frame(int op, byte[] payload) {}

        private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";
        private final Socket socket;
        private final InputStream in;

        private Raw(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
        }

        static Head status(int port, String request) throws IOException {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                return readHead(s.getInputStream());
            }
        }

        static Head upgrade(int port, String path, String host, String origin) throws IOException {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                s.getOutputStream().write(handshake(path, host, origin));
                return readHead(s.getInputStream());
            }
        }

        static Raw connect(int port, String path, String origin) throws IOException {
            Socket s = new Socket("127.0.0.1", port);
            s.setSoTimeout(5000);
            s.getOutputStream().write(handshake(path, "127.0.0.1:" + port, origin));
            Raw raw = new Raw(s);
            Head head = readHead(raw.in);
            assertEquals(101, head.status());
            assertTrue(head.text().contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), head.text());
            return raw;
        }

        private static byte[] handshake(String path, String host, String origin) {
            return ("GET "
                            + path
                            + " HTTP/1.1\r\nHost: "
                            + host
                            + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: "
                            + KEY
                            + "\r\nSec-WebSocket-Version: 13\r\n"
                            + (origin == null ? "" : "Origin: " + origin + "\r\n")
                            + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1);
        }

        private static Head readHead(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (!b.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                int c = in.read();
                if (c < 0) {
                    break;
                }
                b.write(c);
            }
            String text = b.toString(StandardCharsets.ISO_8859_1);
            return new Head(Integer.parseInt(text.split(" ")[1]), text);
        }

        void frame(int op, boolean fin, byte[] payload) throws IOException {
            write(op, fin, payload, true);
        }

        void unmaskedFrame(int op, byte[] payload) throws IOException {
            write(op, true, payload, false);
        }

        private void write(int op, boolean fin, byte[] payload, boolean mask) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write((fin ? 0x80 : 0) | op);
            int m = mask ? 0x80 : 0;
            if (payload.length < 126) {
                b.write(m | payload.length);
            } else {
                b.write(m | 126);
                b.write(payload.length >> 8);
                b.write(payload.length);
            }
            byte[] key = {1, 2, 3, 4};
            if (mask) {
                b.writeBytes(key);
            }
            for (int i = 0; i < payload.length; i++) {
                b.write(mask ? payload[i] ^ key[i % 4] : payload[i]);
            }
            socket.getOutputStream().write(b.toByteArray());
        }

        Frame read() throws IOException {
            int b0 = in.read();
            int len = in.read() & 0x7F;
            if (len == 126) {
                len = (in.read() << 8) | in.read();
            }
            return new Frame(b0 & 0x0F, in.readNBytes(len));
        }

        int readCloseCode() throws IOException {
            Frame f = read();
            assertEquals(0x8, f.op());
            return ((f.payload()[0] & 0xFF) << 8) | (f.payload()[1] & 0xFF);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
