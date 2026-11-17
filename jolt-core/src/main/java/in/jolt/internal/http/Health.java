package in.jolt.internal.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** App-registered health checks, run in parallel with a timeout each. Not API. */
public final class Health {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,63}");

    private record Check(String name, Supplier<Boolean> fn, Duration timeout) {}

    /** The overall verdict and the JSON body: {@code status} and, with checks, {@code checks}. */
    public record Report(boolean up, Map<String, Object> body) {}

    private static final Report EMPTY = new Report(true, Map.of("status", "UP"));

    private final Map<String, Check> checks = new LinkedHashMap<>();
    private volatile List<Check> snapshot = List.of();

    public synchronized void add(String name, Supplier<Boolean> fn, Duration timeout) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid health check name (letters, digits, '_', '.', ':', '-'): " + name);
        }
        if (fn == null) {
            throw new NullPointerException("check");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (checks.containsKey(name)) {
            throw new IllegalStateException("Health check already registered: " + name);
        }
        checks.put(name, new Check(name, fn, timeout));
        snapshot = List.copyOf(checks.values());
    }

    /** Runs every check now. A check is DOWN when it returns false or null, throws or times out. */
    public Report run() {
        List<Check> list = snapshot;
        if (list.isEmpty()) {
            return EMPTY;
        }
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (Check c : list) {
            var f = new CompletableFuture<Boolean>();
            futures.add(f);
            threads.add(
                    Thread.startVirtualThread(
                            () -> {
                                try {
                                    f.complete(c.fn.get());
                                } catch (Throwable t) {
                                    f.completeExceptionally(t);
                                }
                            }));
        }
        Map<String, Object> results = new LinkedHashMap<>();
        boolean up = true;
        for (int i = 0; i < list.size(); i++) {
            Check c = list.get(i);
            boolean ok = outcome(c, futures.get(i), threads.get(i), start);
            results.put(c.name, ok ? "UP" : "DOWN");
            up &= ok;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("checks", results);
        return new Report(up, body);
    }

    private static boolean outcome(
            Check c, CompletableFuture<Boolean> f, Thread worker, long startNanos) {
        long left = c.timeout.toNanos() - (System.nanoTime() - startNanos);
        try {
            return Boolean.TRUE.equals(f.get(Math.max(0, left), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            worker.interrupt();
            LOG.log(System.Logger.Level.WARNING, "Health check " + c.name + " timed out");
        } catch (ExecutionException e) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Health check " + c.name + " failed",
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
