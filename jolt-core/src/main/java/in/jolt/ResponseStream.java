package in.jolt;

import in.jolt.internal.http.StreamSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A response body written incrementally as HTTP chunks on the connection's thread. Writes go out
 * immediately. A write to a client that has gone throws {@link IOException}.
 */
public final class ResponseStream implements AutoCloseable {

    private final StreamSink sink;
    private boolean closed;

    ResponseStream(StreamSink sink) {
        this.sink = sink;
    }

    /** Sends {@code bytes} as one chunk; an empty array sends nothing. */
    public synchronized ResponseStream write(byte[] bytes) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (bytes.length == 0) {
            return this;
        }
        try {
            sink.write(bytes);
        } catch (IOException e) {
            closed = true;
            sink.abort();
            throw e;
        }
        return this;
    }

    /** Sends {@code text} as UTF-8. */
    public ResponseStream write(String text) throws IOException {
        return write(text.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void flush() throws IOException {
        if (!closed) {
            sink.flush();
        }
    }

    /** Ends the body. Calling it again has no effect. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        sink.close();
    }
}
