package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JoltConfig;
import in.jolt.Reply;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.WebSocketHandler;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLSocket;

/** Serves one connection: requests are read, handled and answered strictly one at a time. */
public final class ConnectionHandler implements Runnable {

    @FunctionalInterface
    public interface Dispatcher {
        void dispatch(Request req, Response res) throws Exception;

        /** The reply a registered mapper gives for {@code t}, or {@code null} if none applies. */
        default Reply<?> mapError(Throwable t) {
            return null;
        }

        /** Counters the server updates per connection, or {@code null} when not collecting. */
        default Metrics metrics() {
            return null;
        }
    }

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Socket socket;
    private final JoltConfig config;
    private final Dispatcher dispatcher;
    private final BooleanSupplier draining;
    private final TrustedProxies trusted;
    private volatile boolean idle;
    private final Live wire = new Wire();
    private OutputStream out;
    private InputStream in;
    private boolean taken;
    private boolean websocket;
    private boolean failed;
    private boolean http10;
    private boolean head;
    private StreamSink sink;

    public ConnectionHandler(
            Socket socket,
            JoltConfig config,
            Dispatcher dispatcher,
            BooleanSupplier draining,
            TrustedProxies trusted) {
        this.socket = socket;
        this.config = config;
        this.dispatcher = dispatcher;
        this.draining = draining;
        this.trusted = trusted;
    }

    private final class Wire implements Live {

        @Override
        public StreamSink open(Response res) throws IOException {
            take(false);
            StreamGuard guard =
                    new StreamGuard(socket, config.streamIdleTimeout(), config.streamMaxDuration());
            boolean chunked = !http10;
            try {
                ResponseWriter.writeStreamHead(out, res, chunked);
            } catch (IOException e) {
                guard.close();
                throw e;
            }
            sink = new Sink(guard, chunked, head);
            return sink;
        }

        @Override
        public void upgrade(Request req, String accept, WebSocketHandler handler)
                throws IOException {
            take(true);
            String reply =
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                            + accept
                            + "\r\n\r\n";
            out.write(reply.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            new WebSocketConnection(req, socket, in, out, config, handler).run();
        }
    }

    private final class Sink implements StreamSink {
        private final StreamGuard guard;
        private final boolean chunked;
        private final boolean discard;

        Sink(StreamGuard guard, boolean chunked, boolean discard) {
            this.guard = guard;
            this.chunked = chunked;
            this.discard = discard;
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (discard) {
                return;
            }
            byte[] size = Integer.toHexString(data.length).getBytes(StandardCharsets.US_ASCII);
            int overhead = chunked ? size.length + 4 : 0;
            byte[] buf = new byte[overhead + data.length];
            int at = 0;
            if (chunked) {
                System.arraycopy(size, 0, buf, 0, size.length);
                buf[size.length] = '\r';
                buf[size.length + 1] = '\n';
                at = size.length + 2;
            }
            System.arraycopy(data, 0, buf, at, data.length);
            if (chunked) {
                buf[buf.length - 2] = '\r';
                buf[buf.length - 1] = '\n';
            }
            guard.touch();
            out.write(buf);
            out.flush();
            guard.touch();
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            guard.close();
            if (chunked && !discard) {
                out.write(LAST_CHUNK);
            }
            out.flush();
        }

        @Override
        public void abort() {
            guard.close();
            closeQuietly(socket);
        }
    }

    /** Closes the socket if no request is in flight. */
    public void closeIfIdle() {
        if (idle) {
            closeQuietly(socket);
        }
    }

    /**
     * Answers 503 and closes the socket; used when the connection limit is reached. A TLS socket is
     * only closed: answering would force a handshake on the acceptor thread.
     */
    public static void reject(Socket socket, JoltConfig config) {
        if (socket instanceof SSLSocket) {
            closeQuietly(socket);
            return;
        }
        try (socket) {
            socket.setSoTimeout(1000);
            writeError(
                    socket.getOutputStream(),
                    config,
                    HttpStatus.SERVICE_UNAVAILABLE.code(),
                    "Server busy");
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Rejecting connection failed", e);
        }
    }

    @Override
    public void run() {
        Limits limits = Limits.from(config);
        try (socket) {
            socket.setTcpNoDelay(true);
            if (socket instanceof SSLSocket tls) {
                socket.setSoTimeout((int) config.readTimeout().toMillis());
                tls.startHandshake();
            }
            in = new BufferedInputStream(socket.getInputStream(), 8192);
            out = socket.getOutputStream();
            for (int served = 0; served < config.maxRequestsPerConnection(); served++) {
                socket.setSoTimeout((int) config.keepAliveTimeout().toMillis());
                idle = true;
                if (draining.getAsBoolean()) {
                    return;
                }
                in.mark(1);
                int first;
                try {
                    first = in.read();
                } catch (SocketTimeoutException e) {
                    return;
                }
                idle = false;
                if (first < 0) {
                    return;
                }
                in.reset();
                socket.setSoTimeout((int) config.readTimeout().toMillis());

                HttpParser.ParsedRequest parsed;
                try {
                    parsed = HttpParser.parse(in, limits, out);
                } catch (HttpException e) {
                    writeError(out, config, e.status(), e.getMessage());
                    return;
                } catch (SocketTimeoutException e) {
                    writeError(out, config, 408, "Request Timeout");
                    return;
                } catch (EOFException e) {
                    return;
                }
                if (parsed == null) {
                    return;
                }
                Response res = handle(parsed);
                if (taken) {
                    if (sink != null) {
                        if (failed) {
                            sink.abort();
                        } else {
                            sink.close();
                        }
                    }
                    return;
                }
                boolean keep =
                        parsed.keepAlive()
                                && served + 1 < config.maxRequestsPerConnection()
                                && !draining.getAsBoolean();
                ResponseWriter.write(out, res, keep, parsed.method().equals("HEAD"));
                if (!keep) {
                    return;
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Connection ended", e);
        }
    }

    private void take(boolean ws) throws IOException {
        if (taken) {
            throw new IllegalStateException("Connection already taken over");
        }
        taken = true;
        websocket = ws;
    }

    private Response handle(HttpParser.ParsedRequest p) {
        http10 = p.version().equals("HTTP/1.0");
        head = p.method().equals("HEAD");
        var peer = socket.getInetAddress();
        Request req =
                trusted.request(
                        config,
                        p.method(),
                        p.path(),
                        p.rawQuery(),
                        p.headers(),
                        p.body(),
                        peer == null ? null : peer.getHostAddress(),
                        socket instanceof SSLSocket);
        return respond(dispatcher, config, req, this);
    }

    /**
     * Runs one request through {@code dispatcher} and maps exceptions to error responses. Shared by
     * the socket server and the in-memory test client so both behave identically.
     */
    public static Response respond(Dispatcher dispatcher, JoltConfig config, Request req) {
        return respond(dispatcher, config, req, null);
    }

    private static Response respond(
            Dispatcher dispatcher, JoltConfig config, Request req, ConnectionHandler host) {
        var mapper = config.jsonMapper();
        Response res =
                new Response(mapper)
                        .secureCookies(config.secureCookies())
                        .maxFileBytes(config.maxStaticFileBytes())
                        .live(host == null ? null : host.wire);
        try {
            dispatcher.dispatch(req, res);
            return res;
        } catch (Throwable t) {
            if (host != null && host.taken) {
                host.failed = true;
                if (t instanceof IOException) {
                    LOG.log(System.Logger.Level.DEBUG, "Streaming connection ended", t);
                } else {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Handler failed after streaming began on "
                                    + req.method()
                                    + " "
                                    + req.path(),
                            t);
                }
                return res;
            }
            return failure(dispatcher, config, res, req, t);
        }
    }

    private static Response failure(
            Dispatcher dispatcher, JoltConfig config, Response res, Request req, Throwable t) {
        try {
            Reply<?> reply = dispatcher.mapError(t);
            if (reply != null) {
                if (!(t instanceof HttpException)) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Mapped exception on " + req.method() + " " + req.path(),
                            t);
                }
                return Replies.error(res, reply, config);
            }
        } catch (HttpException e) {
            return Replies.error(res, e, config);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.ERROR, "Error mapper failed", e);
            return internalError(res, config);
        }
        if (t instanceof HttpException e) {
            return Replies.error(res, e, config);
        }
        LOG.log(
                System.Logger.Level.ERROR,
                "Unhandled exception on " + req.method() + " " + req.path(),
                t);
        return internalError(res, config);
    }

    private static Response internalError(Response res, JoltConfig config) {
        return Replies.error(
                res, HttpStatus.INTERNAL_SERVER_ERROR.code(), "Internal Server Error", config);
    }

    private static void writeError(OutputStream out, JoltConfig config, int status, String message)
            throws IOException {
        String type = config.problemJson() ? "application/problem+json" : "text/plain";
        String body =
                config.problemJson()
                        ? "{\"type\":\"about:blank\",\"title\":"
                                + quote(HttpStatus.reasonFor(status))
                                + ",\"status\":"
                                + status
                                + ",\"detail\":"
                                + quote(message)
                                + "}"
                        : message;
        Response res =
                new Response(null)
                        .status(status)
                        .send(body.getBytes(StandardCharsets.UTF_8))
                        .header("Content-Type", type + "; charset=utf-8");
        ResponseWriter.write(out, res, false, false);
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : String.valueOf(s).toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Close failed", e);
        }
    }
}
