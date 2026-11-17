package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.http.Metrics;
import in.jolt.internal.openapi.OpenApi;
import in.jolt.internal.routing.Suggest;
import in.jolt.internal.view.Views;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** An HTTP application: a {@link Router} that can listen on a port. */
public final class App extends Router implements ConnectionHandler.Dispatcher {

    static final class NoRoute extends HttpException {
        NoRoute(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }

    private final JoltConfig config;

    private record Entry(long order, ErrorMapper<?> mapper) {}

    private final Map<Class<?>, Entry> mappers = new ConcurrentHashMap<>();
    private volatile Views views;
    private volatile List<BodyCodec> codecs = List.of();
    private final AtomicLong registrations = new AtomicLong();
    private volatile Metrics metrics;
    private final List<Runnable> startHooks = new ArrayList<>();
    private final List<Runnable> stopHooks = new ArrayList<>();
    private boolean listening;

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
        req.app(this);
        res.request(req);
        run(
                req,
                res,
                req.path(),
                () -> {
                    String hint = Suggest.nearest(routes(), req.method(), req.path());
                    throw new NoRoute(
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

    /**
     * Registers a codec for its media type, replacing an earlier one for the same type. Once any
     * codec is registered, {@link Request#body(Class)} answers 415 for a {@code Content-Type} that
     * is neither JSON nor a registered type.
     */
    public synchronized App codec(BodyCodec codec) {
        List<BodyCodec> next = new ArrayList<>(codecs);
        next.removeIf(c -> c.mediaType().equalsIgnoreCase(codec.mediaType()));
        next.add(codec);
        codecs = List.copyOf(next);
        return this;
    }

    /** Serves {@code res.render(name, model)} from {@code .html} templates under {@code root}. */
    public App views(Path root) {
        return views(root, null);
    }

    /**
     * As {@link #views(Path)}, wrapping every page in the template {@code layout}, which prints the
     * page with {@code {{{content}}}}.
     */
    public App views(Path root, String layout) {
        views = new Views(root, layout);
        return this;
    }

    Views views() {
        return views;
    }

    List<BodyCodec> codecs() {
        return codecs;
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
     * histogram, connections, streams, body bytes). Counts the status each client actually
     * received, after error mappers; streams and WebSockets are counted separately and stay out of
     * the latency histogram.
     */
    public App exposeMetrics() {
        return exposeMetrics(null);
    }

    /**
     * As {@link #exposeMetrics()}, with {@code guard} run before {@code /_jolt/metrics}. A second
     * call has no effect, whatever its guard. May be called after {@link #listen}.
     */
    public App exposeMetrics(Middleware guard) {
        if (metrics != null) {
            return this;
        }
        Metrics m = new Metrics();
        metrics = m;
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
     * Runs {@code hook} before each {@link #listen} accepts its first connection, in registration
     * order. If a hook throws, {@code listen} closes what it opened, runs the stop hooks and throws
     * {@link IllegalStateException}.
     *
     * @throws IllegalStateException if {@link #listen} was already called
     */
    public synchronized App onStart(Runnable hook) {
        register(startHooks, hook);
        return this;
    }

    /**
     * Runs {@code hook} when a server of this app closes, in reverse registration order, after
     * in-flight requests drained. A hook that throws is logged and the rest still run.
     *
     * @throws IllegalStateException if {@link #listen} was already called
     */
    public synchronized App onStop(Runnable hook) {
        register(stopHooks, hook);
        return this;
    }

    private void register(List<Runnable> hooks, Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        if (listening) {
            throw new IllegalStateException("Lifecycle hooks must be registered before listen()");
        }
        hooks.add(hook);
    }

    /**
     * Starts serving on {@code port} (0 picks a free port) on {@link JoltConfig#bindAddress} and
     * returns immediately.
     */
    public Server listen(int port) {
        List<Runnable> start;
        List<Runnable> stop;
        synchronized (this) {
            listening = true;
            start = List.copyOf(startHooks);
            stop = List.copyOf(stopHooks);
        }
        try {
            return Server.start(config, port, this, start, stop);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot listen on port " + port, e);
        }
    }
}
