package in.jolt;

/**
 * Reads and writes one media type. Register with {@link App#codec}; the codec is chosen by {@code
 * Content-Type} for {@link Request#body(Class)} and by {@code Accept} for {@link
 * Response#send(Object)}. JSON stays built in.
 */
public interface BodyCodec {

    /** The media type handled, such as {@code text/csv}, without parameters. */
    String mediaType();

    /**
     * Converts {@code bytes} to {@code type}.
     *
     * @throws IllegalArgumentException if the input is invalid; the client sees a 400 with this
     *     message
     */
    <T> T read(byte[] bytes, Class<T> type);

    byte[] write(Object value);
}
