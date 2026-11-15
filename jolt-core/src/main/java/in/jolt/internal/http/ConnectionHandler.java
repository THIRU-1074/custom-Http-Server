package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JoltConfig;
import in.jolt.Request;
import in.jolt.Response;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;

/** Serves one connection: requests are read, handled and answered strictly one at a time. */
public final class ConnectionHandler implements Runnable {

    @FunctionalInterface
    public interface Dispatcher {
        void dispatch(Request req, Response res) throws Exception;
    }

    private static final System.Logger LOG = System.getLogger("in.jolt");

    private final Socket socket;
    private final JoltConfig config;
    private final Dispatcher dispatcher;
    private final BooleanSupplier draining;
    private volatile boolean idle;

    public ConnectionHandler(
            Socket socket, JoltConfig config, Dispatcher dispatcher, BooleanSupplier draining) {
        this.socket = socket;
        this.config = config;
        this.dispatcher = dispatcher;
        this.draining = draining;
    }

    /** Closes the socket if no request is in flight. */
    public void closeIfIdle() {
        if (idle) {
            closeQuietly(socket);
        }
    }

    /** Answers 503 and closes the socket; used when the connection limit is reached. */
    public static void reject(Socket socket) {
        try (socket) {
            socket.setSoTimeout(1000);
            writeError(
                    socket.getOutputStream(),
                    new Response(null),
                    HttpStatus.SERVICE_UNAVAILABLE.code(),
                    "Server busy");
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Rejecting connection failed", e);
        }
    }

    @Override
    public void run() {
        Limits limits = new Limits(config.maxHeaderBytes(), config.maxBodyBytes());
        try (socket) {
            socket.setTcpNoDelay(true);
            var in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = socket.getOutputStream();
            for (int served = 0; served < config.maxRequestsPerConnection(); served++) {
                socket.setSoTimeout((int) config.keepAliveTimeout().toMillis());
                idle = true;
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
                    parsed = HttpParser.parse(in, limits);
                } catch (HttpException e) {
                    writeError(out, new Response(config.jsonMapper()), e.status(), e.getMessage());
                    return;
                } catch (SocketTimeoutException e) {
                    writeError(out, new Response(config.jsonMapper()), 408, "Request Timeout");
                    return;
                } catch (EOFException e) {
                    return;
                }
                if (parsed == null) {
                    return;
                }
                Response res = handle(parsed);
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
        var mapper = config.jsonMapper();
        Request req =
                new Request(p.method(), p.path(), p.rawQuery(), p.headers(), p.body(), mapper);
        Response res = new Response(mapper);
        try {
            dispatcher.dispatch(req, res);
            return res;
        } catch (HttpException e) {
            return new Response(mapper).status(e.status()).text(String.valueOf(e.getMessage()));
        } catch (Throwable t) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "Unhandled exception on " + p.method() + " " + p.path(),
                    t);
            return new Response(mapper)
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .text("Internal Server Error");
        }
    }

    private static void writeError(OutputStream out, Response res, int status, String message)
            throws IOException {
        res.status(status).text(message);
        ResponseWriter.write(out, res, false, false);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Close failed", e);
        }
    }
}
