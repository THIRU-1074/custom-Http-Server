package in.jolt;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

/** Server-Sent Events over a {@link ResponseStream}: each call sends one complete message. */
public final class EventStream implements AutoCloseable {

    private static final Pattern BREAK = Pattern.compile("\r\n|\r|\n");

    private final ResponseStream stream;

    EventStream(ResponseStream stream) {
        this.stream = stream;
    }

    /**
     * Sends an event; {@code name} may be {@code null}. Line breaks in {@code data} become separate
     * {@code data:} lines.
     *
     * @throws IllegalArgumentException if {@code name} contains a line break
     */
    public EventStream event(String name, String data) throws IOException {
        return event(null, name, data);
    }

    /**
     * Sends an event with an {@code id:} line, which the client returns as {@code Last-Event-ID}
     * when it reconnects; {@code id} and {@code name} may be {@code null}.
     *
     * @throws IllegalArgumentException if {@code id} contains CR, LF or NUL, or {@code name} a line
     *     break
     */
    public EventStream event(String id, String name, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (id != null) {
            if (id.indexOf('\r') >= 0 || id.indexOf('\n') >= 0 || id.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Event id must not contain CR, LF or NUL");
            }
            sb.append("id: ").append(id).append('\n');
        }
        if (name != null) {
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Event name must not contain a line break");
            }
            sb.append("event: ").append(name).append('\n');
        }
        lines(sb, "data: ", data);
        stream.write(sb.append('\n').toString());
        return this;
    }

    /** Sends an unnamed event. */
    public EventStream event(String data) throws IOException {
        return event(null, data);
    }

    /** Sends a comment, ignored by clients; useful as a keep-alive. */
    public EventStream comment(String text) throws IOException {
        stream.write(lines(new StringBuilder(), ": ", text).append('\n').toString());
        return this;
    }

    /** Tells the client how long to wait before reconnecting. */
    public EventStream retry(Duration delay) throws IOException {
        stream.write("retry: " + delay.toMillis() + "\n\n");
        return this;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    private static StringBuilder lines(StringBuilder sb, String prefix, String text) {
        for (String line : BREAK.split(text, -1)) {
            sb.append(prefix).append(line).append('\n');
        }
        return sb;
    }
}
