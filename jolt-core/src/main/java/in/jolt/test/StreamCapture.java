package in.jolt.test;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.Reply;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.WebSocketHandler;
import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.http.Live;
import in.jolt.internal.http.Metrics;
import in.jolt.internal.http.StreamSink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The in-memory connection behind one {@link JoltTest} call: collects what a handler streams. */
final class StreamCapture implements Live, StreamSink {

    private static final class Gone extends IOException {
        Gone() {
            super("Test client disconnected");
        }
    }

    private final int maxEvents;
    private final boolean discard;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Response res;
    private Throwable failure;
    private int events;
    private boolean ended;
    private boolean streaming;

    StreamCapture(int maxEvents, boolean discard) {
        this.maxEvents = maxEvents;
        this.discard = discard;
    }

    /** Dispatches to {@code app} on this connection, keeping a throw after streaming began. */
    ConnectionHandler.Dispatcher dispatcher(App app) {
        return new ConnectionHandler.Dispatcher() {
            @Override
            public void dispatch(Request req, Response r) throws Exception {
                r.live(StreamCapture.this);
                try {
                    app.dispatch(req, r);
                } catch (Exception e) {
                    if (!r.isStreaming()) {
                        throw e;
                    }
                    fail(e);
                }
            }

            @Override
            public Reply<?> mapError(Throwable t, Request req) {
                return app.mapError(t, req);
            }

            @Override
            public Metrics metrics() {
                return app.dispatcher().metrics();
            }
        };
    }

    @Override
    public synchronized StreamSink open(Response r) {
        res = r;
        streaming = true;
        return this;
    }

    @Override
    public void upgrade(Request req, String accept, WebSocketHandler handler) {
        throw new HttpException(HttpStatus.NOT_IMPLEMENTED, "WebSocket needs a live server");
    }

    @Override
    public synchronized void write(byte[] data) throws IOException {
        if (ended) {
            throw new Gone();
        }
        if (!discard) {
            out.write(data, 0, data.length);
        }
        if (maxEvents > 0 && isEvent(data) && ++events >= maxEvents) {
            ended = true;
        }
    }

    private static boolean isEvent(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        return s.endsWith("\n\n") && (s.startsWith("data:") || s.contains("\ndata:"));
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    @Override
    public synchronized void abort() {
        ended = true;
    }

    /** Makes every later write fail, as for a client that left. */
    synchronized void end() {
        ended = true;
    }

    synchronized Response response() {
        return res;
    }

    synchronized boolean streaming() {
        return streaming;
    }

    synchronized byte[] bytes() {
        return out.toByteArray();
    }

    synchronized Throwable failure() {
        return failure;
    }

    private synchronized void fail(Throwable t) {
        if (ended && (t instanceof Gone || t instanceof InterruptedException)) {
            return;
        }
        failure = t;
    }
}
