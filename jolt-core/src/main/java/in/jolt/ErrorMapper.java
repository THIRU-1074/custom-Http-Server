package in.jolt;

/** Turns an exception of type {@code E} into a reply. Register with {@link App#onError}. */
@FunctionalInterface
public interface ErrorMapper<E> {
    Reply<?> map(E error);
}
