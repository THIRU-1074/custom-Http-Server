package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.openapi.OpenApi;
import in.jolt.internal.routing.Suggest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** An HTTP application: a {@link Router} that can listen on a port. */
public final class App extends Router implements ConnectionHandler.Dispatcher {

    private final JoltConfig config;
    private final Map<Class<?>, ErrorMapper<?>> mappers = new HashMap<>();

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
     * Maps exceptions of {@code type} to a reply. The most specific registered class in the
     * exception's hierarchy wins, whatever the registration order. Unmapped exceptions keep the
     * default handling.
     */
    public <E extends Throwable> App onError(Class<E> type, ErrorMapper<E> mapper) {
        mappers.put(type, mapper);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Reply<?> mapError(Throwable t) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            ErrorMapper<Throwable> m = (ErrorMapper<Throwable>) mappers.get(c);
            if (m != null) {
                return m.map(t);
            }
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
