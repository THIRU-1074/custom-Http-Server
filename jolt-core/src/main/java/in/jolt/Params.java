package in.jolt;

import in.jolt.internal.http.Values;

/** Read-only view of a request's path and query parameters, for handlers that need no more. */
public interface Params {

    /** Value of a {@code :name} path parameter, or {@code null}. */
    String param(String name);

    /** First value of a query parameter, or {@code null}. */
    String query(String name);

    /**
     * Path parameter converted to {@code type}, or {@code null} if absent. Supports String,
     * Integer, Long, Boolean, Double, UUID, Instant, LocalDate and enums (case-insensitive),
     * primitives included.
     *
     * @throws HttpException 400 naming the parameter if the value is malformed
     */
    default <T> T param(String name, Class<T> type) {
        return Values.convert("path", name, param(name), type);
    }

    /**
     * Query parameter converted like {@link #param(String, Class)}, or {@code fallback} when
     * absent.
     */
    default <T> T query(String name, Class<T> type, T fallback) {
        String raw = query(name);
        return raw == null ? fallback : Values.convert("query", name, raw, type);
    }
}
