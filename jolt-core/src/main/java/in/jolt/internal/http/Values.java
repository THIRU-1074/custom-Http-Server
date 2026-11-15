package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/** Converts raw parameter text to a supported type; shared by path and query parameters. */
public final class Values {

    private static final Map<Class<?>, Class<?>> WRAPPERS =
            Map.of(
                    int.class, Integer.class,
                    long.class, Long.class,
                    boolean.class, Boolean.class,
                    double.class, Double.class);

    private Values() {}

    /**
     * Converts {@code raw} to {@code type}; {@code null} stays {@code null}.
     *
     * @throws HttpException 400 naming {@code kind} and {@code name} if {@code raw} is malformed
     * @throws UnsupportedOperationException if {@code type} is not supported
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(String kind, String name, String raw, Class<T> type) {
        if (raw == null) {
            return null;
        }
        try {
            return (T) parse(raw, WRAPPERS.getOrDefault(type, type));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new HttpException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid "
                            + kind
                            + " parameter '"
                            + name
                            + "': expected "
                            + type.getSimpleName());
        }
    }

    private static Object parse(String raw, Class<?> t) {
        if (t == String.class) {
            return raw;
        } else if (t == Integer.class) {
            return Integer.valueOf(raw);
        } else if (t == Long.class) {
            return Long.valueOf(raw);
        } else if (t == Boolean.class) {
            if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
                return Boolean.valueOf(raw);
            }
            throw new IllegalArgumentException(raw);
        } else if (t == Double.class) {
            double d = Double.parseDouble(raw);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException(raw);
            }
            return d;
        } else if (t == UUID.class) {
            return UUID.fromString(raw);
        } else if (t == Instant.class) {
            return Instant.parse(raw);
        } else if (t == LocalDate.class) {
            return LocalDate.parse(raw);
        } else if (t.isEnum()) {
            for (Object c : t.getEnumConstants()) {
                if (((Enum<?>) c).name().equalsIgnoreCase(raw)) {
                    return c;
                }
            }
            throw new IllegalArgumentException(raw);
        }
        throw new UnsupportedOperationException("Unsupported parameter type " + t.getName());
    }
}
