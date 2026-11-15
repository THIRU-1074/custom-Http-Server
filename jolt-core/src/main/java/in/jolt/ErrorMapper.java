package in.jolt;

/** Turns an exception of type {@code E} into a reply. Register with {@link App#onError}. */
@FunctionalInterface
public interface ErrorMapper<E extends Throwable> {
    Reply<?> map(E error);
}
