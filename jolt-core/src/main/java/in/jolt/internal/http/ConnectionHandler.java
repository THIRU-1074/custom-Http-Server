package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JoltConfig;
import in.jolt.Reply;
import in.jolt.Request;
import in.jolt.Response;
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

    private final Socket socket;
    private final JoltConfig config;
    private final Dispatcher dispatcher;
    private final BooleanSupplier draining;
    private final TrustedProxies trusted;
    private volatile boolean idle;
    private OutputStream out;
    private InputStream in;
    private boolean taken;

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

    /** The connection's output, for handlers that take it over. */
    public OutputStream out() {
        return out;
    }

    /**
     * Marks the connection as taken over by the handler: no response is written, it then closes.
     */
    public void take() {
        taken = true;
    }

    /** The connection's input, positioned after the request headers. */
    public InputStream in() {
        return in;
    }

    public JoltConfig config() {
        return config;
    }

    /** The socket, for handlers that take the connection over. */
    public Socket socket() {
        return socket;
    }

    /** A guard applying the stream limits of the config to this connection. */
    public StreamGuard guard() {
        return guard(config.streamIdleTimeout(), config.streamMaxDuration());
    }

    /** A guard with explicit limits, see {@link StreamGuard}. */
    public StreamGuard guard(java.time.Duration idle, java.time.Duration max) {
        return new StreamGuard(socket, idle, max);
    }

    /** Closes the socket if no request is in flight. */
    public void closeIfIdle() {
        if (idle) {
            closeQuietly(socket);
        }
    }

    /** Answers 503 and closes the socket; used when the connection limit is reached. */
    public static void reject(Socket socket, JoltConfig config) {
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
                    if (res.isStreaming()) {
                        res.stream().close();
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

    private Response handle(HttpParser.ParsedRequest p) {
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
                        .host(host);
        try {
            dispatcher.dispatch(req, res);
            return res;
        } catch (Throwable t) {
            if (host != null && host.taken) {
                LOG.log(System.Logger.Level.DEBUG, "Streaming handler ended with " + t, t);
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
