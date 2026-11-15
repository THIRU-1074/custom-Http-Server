package in.jolt;

/** Step in the request chain. Call {@code next.run()} to continue, or respond to stop the chain. */
@FunctionalInterface
public interface Middleware {

    void handle(Request req, Response res, Next next) throws Exception;

    /** Runs this middleware, then {@code after} when this one calls {@code next.run()}. */
    default Middleware then(Middleware after) {
        return (req, res, next) -> handle(req, res, () -> after.handle(req, res, next));
    }

    @FunctionalInterface
    interface Next {
        void run() throws Exception;
    }
}
