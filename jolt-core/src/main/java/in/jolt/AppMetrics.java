package in.jolt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * App-defined counters and gauges, served by {@link App#exposeMetrics()} after Jolt's own series.
 * Register before or after {@code exposeMetrics} and {@code listen}. Names must match the
 * Prometheus grammar and not start with {@code jolt_}; label sets are fixed at registration.
 */
public final class AppMetrics {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final Pattern LABEL = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    /** A monotonically increasing count, safe to update from any thread. */
    public static final class Counter {
        private final LongAdder adder = new LongAdder();

        Counter() {}

        public void increment() {
            adder.increment();
        }

        /** Adds {@code n}, which must not be negative. */
        public void add(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("A counter cannot decrease: " + n);
            }
            adder.add(n);
        }

        public long value() {
            return adder.sum();
        }
    }

    private record Series(String labels, Counter counter, LongSupplier gauge) {}

    private static final class Family {
        final String help;
        final boolean counter;
        final List<Series> series = new ArrayList<>();

        Family(String help, boolean counter) {
            this.help = help;
            this.counter = counter;
        }
    }

    private final Map<String, Family> families = new LinkedHashMap<>();

    AppMetrics() {}

    /**
     * Registers a counter.
     *
     * @throws IllegalArgumentException for an invalid name
     * @throws IllegalStateException if the name is already registered
     */
    public Counter counter(String name, String help) {
        return counter(name, help, Map.of());
    }

    /**
     * As {@link #counter(String, String)}, with fixed labels; one name may have many label sets.
     */
    public Counter counter(String name, String help, Map<String, String> labels) {
        Counter c = new Counter();
        register(name, help, labels, new Series(format(labels), c, null), true);
        return c;
    }

    void counter(String name, String help, Counter c) {
        register(name, help, Map.of(), new Series("", c, null), true);
    }

    /**
     * Registers a gauge read on every scrape. A supplier that throws is left out of that scrape.
     *
     * @throws IllegalArgumentException for an invalid name
     * @throws IllegalStateException if the name is already registered
     */
    public AppMetrics gauge(String name, String help, LongSupplier value) {
        return gauge(name, help, Map.of(), value);
    }

    /** As {@link #gauge(String, String, LongSupplier)}, with fixed labels. */
    public AppMetrics gauge(
            String name, String help, Map<String, String> labels, LongSupplier value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        register(name, help, labels, new Series(format(labels), null, value), false);
        return this;
    }

    private synchronized void register(
            String name, String help, Map<String, String> labels, Series s, boolean counter) {
        if (name == null || !NAME.matcher(name).matches() || name.startsWith("jolt_")) {
            throw new IllegalArgumentException(
                    "Invalid metric name (letters, digits, '_' and ':'; not jolt_*): " + name);
        }
        if (help == null) {
            throw new IllegalArgumentException("help is required for " + name);
        }
        Family f = families.get(name);
        if (f != null && f.counter != counter) {
            throw new IllegalStateException(
                    "Metric " + name + " is already registered as a " + kind(f.counter));
        }
        if (f != null && f.series.stream().anyMatch(o -> o.labels.equals(s.labels))) {
            throw new IllegalStateException("Metric already registered: " + name + s.labels);
        }
        if (f == null) {
            f = new Family(help, counter);
            families.put(name, f);
        }
        f.series.add(s);
    }

    private static String kind(boolean counter) {
        return counter ? "counter" : "gauge";
    }

    private static String format(Map<String, String> labels) {
        if (labels.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : new TreeMap<>(labels).entrySet()) {
            String k = e.getKey();
            if (k == null || !LABEL.matcher(k).matches() || k.startsWith("__")) {
                throw new IllegalArgumentException("Invalid label name: " + k);
            }
            if (e.getValue() == null) {
                throw new IllegalArgumentException("Null value for label " + k);
            }
            sb.append(sb.length() > 1 ? "," : "").append(k).append("=\"");
            sb.append(escape(e.getValue(), true)).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s, boolean quotes) {
        String out = s.replace("\\", "\\\\").replace("\n", "\\n");
        return quotes ? out.replace("\"", "\\\"") : out;
    }

    /** The registered series in Prometheus text format, empty when none. */
    synchronized String render() {
        if (families.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Family> e : families.entrySet()) {
            String name = e.getKey();
            Family f = e.getValue();
            sb.append("# HELP ")
                    .append(name)
                    .append(' ')
                    .append(escape(f.help, false))
                    .append('\n');
            sb.append("# TYPE ").append(name).append(' ').append(kind(f.counter)).append('\n');
            for (Series s : f.series) {
                Long v = value(name, s);
                if (v != null) {
                    sb.append(name).append(s.labels).append(' ').append(v).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static Long value(String name, Series s) {
        if (s.counter != null) {
            return s.counter.value();
        }
        try {
            return s.gauge.getAsLong();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Gauge " + name + " failed", e);
            return null;
        }
    }
}
