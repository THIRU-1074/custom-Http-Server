package in.jolt;

/** Read-only view of a request's path and query parameters, for handlers that need no more. */
public interface Params {

    /** Value of a {@code :name} path parameter, or {@code null}. */
    String param(String name);

    /** First value of a query parameter, or {@code null}. */
    String query(String name);
}
