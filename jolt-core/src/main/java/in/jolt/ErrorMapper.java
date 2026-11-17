package in.jolt;

/**
 * Turns an exception of type {@code E} into a reply. Register with {@link App#onError}. The error
 * reply keeps every header middleware set, Set-Cookie included, except Content-Type, Content-Length
 * and Transfer-Encoding, which the reply body sets.
 */
@FunctionalInterface
public interface ErrorMapper<E> {
    Reply<?> map(E error);

    /** As {@link ErrorMapper}, also given the failed request: {@code (e, req) -> ...}. */
    @FunctionalInterface
    interface WithRequest<E> {
        Reply<?> map(E error, Request req);
    }
}
