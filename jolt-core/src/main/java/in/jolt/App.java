package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import java.io.IOException;
import java.io.UncheckedIOException;

/** An HTTP application: a {@link Router} that can listen on a port. */
public final class App extends Router implements ConnectionHandler.Dispatcher {

    private final JoltConfig config;

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
                    throw new HttpException(
                            HttpStatus.NOT_FOUND, "Cannot " + req.method() + " " + req.path());
                });
    }

    /** Starts serving on {@code port} (0 picks a free port) and returns immediately. */
    public Server listen(int port) {
        try {
            return new Server(config, port, this);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot listen on port " + port, e);
        }
    }
}
