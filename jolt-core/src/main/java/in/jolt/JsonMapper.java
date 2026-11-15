package in.jolt;

/** Converts objects to and from JSON. Replace via {@link JoltConfig#jsonMapper(JsonMapper)}. */
public interface JsonMapper {

    /** Serializes {@code value} to UTF-8 JSON. */
    byte[] write(Object value);

    /**
     * Parses JSON and binds it to {@code type}.
     *
     * @throws IllegalArgumentException if the input is not valid JSON or does not fit the type
     */
    <T> T read(byte[] json, Class<T> type);
}
