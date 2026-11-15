package in.jolt;

/** A typed route result: a status and an optional JSON body ({@code null} sends no body). */
public record Reply<T>(int status, T body) {

    public static <T> Reply<T> created(T body) {
        return new Reply<>(HttpStatus.CREATED.code(), body);
    }

    public static <T> Reply<T> status(int status, T body) {
        return new Reply<>(status, body);
    }
}
