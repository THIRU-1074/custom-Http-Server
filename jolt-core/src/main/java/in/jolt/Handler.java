package in.jolt;

/** Terminal route handler. */
@FunctionalInterface
public interface Handler {
    void handle(Request req, Response res) throws Exception;
}
