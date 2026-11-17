package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.http.Health;
import in.jolt.internal.http.Metrics;
import in.jolt.internal.openapi.OpenApi;
import in.jolt.internal.routing.Suggest;
import in.jolt.internal.view.Views;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** An HTTP application: a {@link Router} that can listen on a port. */
public final class App extends Router {

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
    private volatile Metrics collector;
    private final AppMetrics appMetrics = new AppMetrics();
    private final Health health = new Health();
    private final List<Server.Hook> hooks = new ArrayList<>();
    private boolean listening;

    private final ConnectionHandler.Dispatcher dispatcher =
            new ConnectionHandler.Dispatcher() {
                @Override
                public void dispatch(Request req, Response res) throws Exception {
                    App.this.dispatch(req, res);
                }

                @Override
                public Reply<?> mapError(Throwable t) {
                    return App.this.mapError(t);
                }

                @Override
                public Metrics metrics() {
                    return collector;
                }
            };

    App(JoltConfig config) {
        this.config = config;
    }

    /** The server's view of this app. Not API. */
    public ConnectionHandler.Dispatcher dispatcher() {
        return dispatcher;
    }

    public JoltConfig config() {
        return config;
    }

    /**
     * Runs the middleware chain and matching route for one request.
     *
     * @throws HttpException 404 when no route matches
     */
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

    /** App-defined counters and gauges, added to {@code /_jolt/metrics} once it is exposed. */
    public AppMetrics metrics() {
        return appMetrics;
    }

    /** As {@link #health(String, Supplier, Duration)} with a 2 second timeout. */
    public App health(String name, Supplier<Boolean> check) {
        return health(name, check, Duration.ofSeconds(2));
    }

    /**
     * Registers a health check for {@code /_jolt/health}. The check passes by returning {@code
     * true}; {@code false}, {@code null}, a throw or exceeding {@code timeout} marks it DOWN. The
     * response never carries error details. Callable before or after {@link #exposeMetrics()} and
     * {@link #listen}.
     *
     * @throws IllegalArgumentException for an invalid name or non-positive timeout
     * @throws IllegalStateException if the name is already registered
     */
    public App health(String name, Supplier<Boolean> check, Duration timeout) {
        health.add(name, check, timeout);
        return this;
    }

    /**
     * Opt-in monitoring: {@code GET /_jolt/health} ({@code {"status":"UP"}}, or 200 or 503 with
     * {@code checks} once {@link #health} checks exist) and {@code GET /_jolt/metrics} (Prometheus
     * text: request counts by method and status class, latency histogram, connections, streams,
     * body bytes, then the {@link #metrics()} series). Counts the status each client actually
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
        if (collector != null) {
            return this;
        }
        Metrics m = new Metrics();
        collector = m;
        get(
                "/_jolt/health",
                (req, res) -> {
                    Health.Report report = health.run();
                    res.status(report.up() ? 200 : 503).json(report.body());
                });
        Handler scrape =
                (req, res) ->
                        res.send(
                                        (m.render() + appMetrics.render())
                                                .getBytes(StandardCharsets.UTF_8))
                                .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        if (guard == null) {
            get("/_jolt/metrics", scrape);
        } else {
            get("/_jolt/metrics", guard, scrape);
        }
        return this;
    }

    /**
     * Runs {@code hook} before {@link #listen} binds the port, in order; a throw aborts {@code
     * listen}. Register before {@code listen}, else {@code IllegalStateException}.
     */
    public App onStart(Runnable hook) {
        return register(hook, null);
    }

    /**
     * Runs {@code hook} on {@link Server#close()} after in-flight requests drained, in reverse
     * registration order; a failure is logged and the rest still run.
     */
    public App onStop(Runnable hook) {
        return register(null, hook);
    }

    /**
     * Registers {@code start} and {@code stop} as a pair: if a later start hook fails, {@code stop}
     * runs only when {@code start} completed.
     */
    public App lifecycle(Runnable start, Runnable stop) {
        return register(
                Objects.requireNonNull(start, "start"), Objects.requireNonNull(stop, "stop"));
    }

    private synchronized App register(Runnable start, Runnable stop) {
        if (listening) {
            throw new IllegalStateException("Lifecycle hooks must be registered before listen()");
        }
        if (start == null && stop == null) {
            throw new NullPointerException("hook");
        }
        hooks.add(new Server.Hook(start, stop));
        return this;
    }

    /**
     * Starts serving on {@code port} (0 picks a free port) on {@link JoltConfig#bindAddress} and
     * returns immediately.
     */
    public synchronized Server listen(int port) {
        listening = true;
        boolean started = false;
        try {
            Server server = Server.start(config, port, dispatcher, List.copyOf(hooks));
            started = true;
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot listen on port " + port, e);
        } finally {
            listening = started;
        }
    }
}
