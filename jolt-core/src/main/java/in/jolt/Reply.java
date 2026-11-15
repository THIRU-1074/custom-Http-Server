package in.jolt;

/**
 * A typed route result: a status (100-599) and an optional JSON body ({@code null} sends no body).
 * Statuses 1xx, 204 and 304 allow no body.
 */
public record Reply<T>(int status, T body) {

    public Reply {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Status must be 100-599, got " + status);
        }
        if (body != null && (status < 200 || status == 204 || status == 304)) {
            throw new IllegalArgumentException("Status " + status + " cannot have a body");
        }
    }

    public static <T> Reply<T> created(T body) {
        return new Reply<>(HttpStatus.CREATED.code(), body);
    }

    public static <T> Reply<T> status(int status, T body) {
        return new Reply<>(status, body);
    }
}
