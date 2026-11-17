package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.http.Metrics;
import in.jolt.internal.openapi.OpenApi;
import in.jolt.internal.routing.Suggest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** An HTTP application: a {@link Router} that can listen on a port. */
public final class App extends Router implements ConnectionHandler.Dispatcher {

    private final JoltConfig config;

    private record Entry(long order, ErrorMapper<?> mapper) {}

    private final Map<Class<?>, Entry> mappers = new ConcurrentHashMap<>();
    private final AtomicLong registrations = new AtomicLong();
    private volatile Metrics metrics;

    App(JoltConfig config) {
        this.config = config;
    }

    public JoltConfig config() {
        return config;
    }

    /**
     * Runs the middleware chain and matching route for one request.
     *
     * @throws HttpException 404 when no route matches
     */
    @Override
    public void dispatch(Request req, Response res) throws Exception {
        run(
                req,
                res,
                req.path(),
                () -> {
                    String hint = Suggest.nearest(routes(), req.method(), req.path());
                    throw new HttpException(
                            HttpStatus.NOT_FOUND,
                            "No route "
                                    + req.method()
                                    + " "
                                    + req.path()
                                    + (hint == null ? "" : ", did you mean " + hint + "?"));
                });
    }

    /**
     * Maps exceptions of {@code type} (a class or an interface) to a reply. The registered type
     * closest to the exception's class, counting superclass and interface steps breadth-first,
     * wins; ties go to the first registered. Safe to call after {@link #listen}, though registering
     * before it is the norm. Unmapped exceptions keep the default handling. An {@link
     * HttpException} is mapped only by a mapper registered for {@code HttpException} or a subclass,
     * never by a broader type such as {@code RuntimeException}.
     */
    public <E> App onError(Class<E> type, ErrorMapper<E> mapper) {
        mappers.put(type, new Entry(registrations.getAndIncrement(), mapper));
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Reply<?> mapError(Throwable t) {
        boolean http = t instanceof HttpException;
        Set<Class<?>> seen = new HashSet<>();
        List<Class<?>> level = List.of(t.getClass());
        while (!level.isEmpty()) {
            Entry best = null;
            for (Class<?> c : level) {
                Entry e = http && !HttpException.class.isAssignableFrom(c) ? null : mappers.get(c);
                if (e != null && (best == null || e.order < best.order)) {
                    best = e;
                }
            }
            if (best != null) {
                return ((ErrorMapper<Object>) best.mapper).map(t);
            }
            List<Class<?>> next = new ArrayList<>();
            for (Class<?> c : level) {
                if (c.getSuperclass() != null && seen.add(c.getSuperclass())) {
                    next.add(c.getSuperclass());
                }
                for (Class<?> i : c.getInterfaces()) {
                    if (seen.add(i)) {
                        next.add(i);
                    }
                }
            }
            level = next;
        }
        return null;
    }

    /** Minimal OpenAPI 3.1 JSON describing the registered routes. */
    public String openApi() {
        return OpenApi.generate(routes(), "Jolt API");
    }

    /** Opt-in introspection: {@code GET /_jolt/routes} and {@code GET /_jolt/openapi.json}. */
    public App exposeRoutes() {
        get("/_jolt/routes", (req, res) -> res.json(routes()));
        get(
                "/_jolt/openapi.json",
                (req, res) ->
                        res.send(openApi().getBytes(StandardCharsets.UTF_8))
                                .header("Content-Type", "application/json; charset=utf-8"));
        return this;
    }

    @Override
    public Metrics metrics() {
        return metrics;
    }

    /**
     * Opt-in monitoring: {@code GET /_jolt/health} ({@code {"status":"UP"}}) and {@code GET
     * /_jolt/metrics} (Prometheus text: request counts by method and status class, latency
     * histogram, connections, body bytes). Call it before other middleware so every request is
     * counted. Exceptions other than {@link HttpException} count as 500.
     */
    public App exposeMetrics() {
        return exposeMetrics(null);
    }

    /** As {@link #exposeMetrics()}, with {@code guard} run before {@code /_jolt/metrics}. */
    public App exposeMetrics(Middleware guard) {
        Metrics m = new Metrics();
        metrics = m;
        use(m.middleware());
        get("/_jolt/health", (req, res) -> res.json(Map.of("status", "UP")));
        Handler scrape =
                (req, res) ->
                        res.send(m.render().getBytes(StandardCharsets.UTF_8))
                                .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        if (guard == null) {
            get("/_jolt/metrics", scrape);
        } else {
            get("/_jolt/metrics", guard, scrape);
        }
        return this;
    }

    /**
     * Starts serving on {@code port} (0 picks a free port) on {@link JoltConfig#bindAddress} and
     * returns immediately.
     */
    public Server listen(int port) {
        try {
            return new Server(config, port, this);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot listen on port " + port, e);
        }
    }
}
