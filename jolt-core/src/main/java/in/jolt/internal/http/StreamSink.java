package in.jolt.internal.http;

import java.io.IOException;

/** Where a streamed response body goes. Not API. */
public interface StreamSink {

    /** Sends one piece of the body. */
    void write(byte[] data) throws IOException;

    void flush() throws IOException;

    /** Ends the body cleanly. */
    void close() throws IOException;

    /** Drops the connection without a clean end, so the client sees a truncated body. */
    void abort();
}
