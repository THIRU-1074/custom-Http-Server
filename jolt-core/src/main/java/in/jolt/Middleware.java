package in.jolt;

/** Step in the request chain. Call {@code next.run()} to continue, or respond to stop the chain. */
@FunctionalInterface
public interface Middleware {

    void handle(Request req, Response res, Next next) throws Exception;

    @FunctionalInterface
    interface Next {
        void run() throws Exception;
    }
}
