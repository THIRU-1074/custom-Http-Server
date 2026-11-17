package in.jolt;

import in.jolt.internal.http.Values;
import java.util.ArrayList;
import java.util.List;

/** Read-only view of a request's path and query parameters, for handlers that need no more. */
public interface Params {

    /** Value of a {@code :name} path parameter, or {@code null}. */
    String param(String name);

    /** First value of a query parameter, or {@code null}. */
    String query(String name);

    /** Every value of a query parameter in order; empty if absent. */
    default List<String> queryAll(String name) {
        String first = query(name);
        return first == null ? List.of() : List.of(first);
    }

    /**
     * Path parameter as String, a primitive or wrapper, UUID, Instant, LocalDate or enum.
     *
     * @throws HttpException 400 if malformed, or absent for a primitive
     */
    default <T> T param(String name, Class<T> type) {
        return Values.convert("path", name, param(name), type, null);
    }

    /**
     * Query parameter as {@code type}, see {@link #param(String, Class)}; {@code fallback} if
     * absent.
     */
    default <T> T query(String name, Class<T> type, T fallback) {
        return Values.convert("query", name, query(name), type, fallback);
    }

    /**
     * Every value of a query parameter as {@code type}, see {@link #param(String, Class)}.
     *
     * @throws HttpException 400 naming the parameter if any value is malformed
     */
    default <T> List<T> queryAll(String name, Class<T> type) {
        List<String> raw = queryAll(name);
        List<T> out = new ArrayList<>(raw.size());
        for (String v : raw) {
            out.add(Values.convert("query", name, v, type, null));
        }
        return List.copyOf(out);
    }
}
