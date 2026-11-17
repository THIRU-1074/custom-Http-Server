package in.jolt;

import in.jolt.internal.http.StreamGuard;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A response body written incrementally as HTTP chunks on the connection's thread. Writes go out
 * immediately. A write to a client that has gone throws {@link IOException}.
 */
public final class ResponseStream implements AutoCloseable {

    private static final byte[] LAST = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final OutputStream out;
    private final StreamGuard guard;
    private boolean closed;

    ResponseStream(OutputStream out, StreamGuard guard) {
        this.out = out;
        this.guard = guard;
    }

    /** Sends {@code bytes} as one chunk; an empty array sends nothing. */
    public synchronized ResponseStream write(byte[] bytes) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (bytes.length == 0) {
            return this;
        }
        byte[] size = Integer.toHexString(bytes.length).getBytes(StandardCharsets.US_ASCII);
        byte[] chunk = new byte[size.length + bytes.length + 4];
        System.arraycopy(size, 0, chunk, 0, size.length);
        chunk[size.length] = '\r';
        chunk[size.length + 1] = '\n';
        System.arraycopy(bytes, 0, chunk, size.length + 2, bytes.length);
        chunk[chunk.length - 2] = '\r';
        chunk[chunk.length - 1] = '\n';
        try {
            guard.touch();
            out.write(chunk);
            out.flush();
            guard.touch();
        } catch (IOException e) {
            closed = true;
            guard.close();
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
            out.flush();
        }
    }

    /** Ends the body. Calling it again has no effect. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        guard.close();
        out.write(LAST);
        out.flush();
    }
}
