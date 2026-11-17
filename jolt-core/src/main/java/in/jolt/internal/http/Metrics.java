package in.jolt.internal.http;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Request and connection counters in the Prometheus text format. Label values come from fixed sets
 * (method, status class, bucket bound), never from request data.
 */
public final class Metrics {

    private static final String[] METHODS = {
        "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "OTHER"
    };
    private static final String[] CLASSES = {"1xx", "2xx", "3xx", "4xx", "5xx"};
    private static final double[] BOUNDS = {
        0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10
    };

    private final LongAdder[][] requests = new LongAdder[METHODS.length][CLASSES.length];
    private final LongAdder[] buckets = new LongAdder[BOUNDS.length + 1];
    private final LongAdder nanos = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder opened = new LongAdder();
    private final LongAdder closed = new LongAdder();
    private final LongAdder streams = new LongAdder();
    private final LongAdder streamsOpen = new LongAdder();
    private final LongAdder sockets = new LongAdder();
    private final LongAdder socketsOpen = new LongAdder();
    private volatile Supplier<String> section;

    public Metrics() {
        for (LongAdder[] row : requests) {
            for (int i = 0; i < row.length; i++) {
                row[i] = new LongAdder();
            }
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    public void connectionOpened() {
        opened.increment();
    }

    public void connectionClosed() {
        closed.increment();
    }

    /** Counts a finished request, including its latency and body sizes. */
    public void record(String method, int status, long elapsedNanos, long in, long out) {
        count(method, status);
        double seconds = elapsedNanos / 1e9;
        int b = 0;
        while (b < BOUNDS.length && seconds > BOUNDS[b]) {
            b++;
        }
        buckets[b].increment();
        nanos.add(elapsedNanos);
        bytesIn.add(in);
        bytesOut.add(out);
    }

    /** Counts a request that became a stream or WebSocket; it has no latency to report. */
    public void recordStreamed(String method, int status, long in) {
        count(method, status);
        bytesIn.add(in);
    }

    /** A stream or WebSocket began. */
    public void streamStarted(boolean websocket) {
        (websocket ? sockets : streams).increment();
        (websocket ? socketsOpen : streamsOpen).increment();
    }

    public void streamEnded(boolean websocket) {
        (websocket ? socketsOpen : streamsOpen).decrement();
    }

    private void count(String method, int status) {
        int m = METHODS.length - 1;
        for (int i = 0; i < m; i++) {
            if (METHODS[i].equals(method)) {
                m = i;
                break;
            }
        }
        int c = Math.max(0, Math.min(CLASSES.length - 1, status / 100 - 1));
        requests[m][c].increment();
    }

    /** The current values in Prometheus text exposition format 0.0.4. */
    public String render() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# HELP jolt_http_requests_total Requests by method and status class.\n");
        sb.append("# TYPE jolt_http_requests_total counter\n");
        for (int m = 0; m < METHODS.length; m++) {
            for (int c = 0; c < CLASSES.length; c++) {
                long n = requests[m][c].sum();
                if (n > 0) {
                    sb.append("jolt_http_requests_total{method=\"")
                            .append(METHODS[m])
                            .append("\",class=\"")
                            .append(CLASSES[c])
                            .append("\"} ")
                            .append(n)
                            .append('\n');
                }
            }
        }
        String hist = "jolt_http_request_duration_seconds";
        sb.append("# HELP ").append(hist).append(" Request latency.\n");
        sb.append("# TYPE ").append(hist).append(" histogram\n");
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i].sum();
            sb.append(hist)
                    .append("_bucket{le=\"")
                    .append(i < BOUNDS.length ? Double.toString(BOUNDS[i]) : "+Inf")
                    .append("\"} ")
                    .append(cumulative)
                    .append('\n');
        }
        sb.append(hist).append("_sum ").append(nanos.sum() / 1e9).append('\n');
        sb.append(hist).append("_count ").append(cumulative).append('\n');
        long open = opened.sum();
        long done = closed.sum();
        counter(sb, "jolt_connections_total", "Connections accepted.", open);
        sb.append("# HELP jolt_connections_active Connections currently open.\n");
        sb.append("# TYPE jolt_connections_active gauge\n");
        sb.append("jolt_connections_active ").append(Math.max(0, open - done)).append('\n');
        counter(sb, "jolt_streams_total", "Streamed responses started.", streams.sum());
        gauge(sb, "jolt_streams_active", "Streamed responses open.", streamsOpen.sum());
        counter(
                sb,
                "jolt_websocket_connections_total",
                "WebSocket connections opened.",
                sockets.sum());
        gauge(
                sb,
                "jolt_websocket_connections_active",
                "WebSocket connections open.",
                socketsOpen.sum());
        counter(sb, "jolt_http_request_body_bytes_total", "Request body bytes.", bytesIn.sum());
        counter(sb, "jolt_http_response_body_bytes_total", "Response body bytes.", bytesOut.sum());
        Supplier<String> extra = section;
        if (extra != null) {
            sb.append(extra.get());
        }
        return sb.toString();
    }

    /** Appends {@code text} (already in exposition format) after the built-in series. */
    public void section(Supplier<String> text) {
        section = text;
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
