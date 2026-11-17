package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Converts raw parameter text to a supported type; shared by path and query parameters. */
public final class Values {

    private static final Map<Class<?>, Class<?>> WRAPPERS =
            Map.of(
                    int.class, Integer.class,
                    long.class, Long.class,
                    short.class, Short.class,
                    byte.class, Byte.class,
                    boolean.class, Boolean.class,
                    double.class, Double.class,
                    float.class, Float.class);

    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    private Values() {}

    /**
     * Converts {@code raw} to {@code type}. When {@code raw} is absent the result is {@code
     * fallback}, or {@code null} for an object type.
     *
     * @throws HttpException 400 if {@code raw} is malformed, or absent for a primitive with no
     *     fallback
     * @throws IllegalArgumentException if {@code type} is not supported
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(String kind, String name, String raw, Class<T> type, T fallback) {
        Class<?> t = WRAPPERS.getOrDefault(type, type);
        if (!supported(t)) {
            throw new IllegalArgumentException("Unsupported parameter type " + type.getName());
        }
        if (raw == null) {
            if (fallback == null && type.isPrimitive()) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Missing parameter " + name);
            }
            return fallback;
        }
        try {
            return (T) parse(raw, t);
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

    private static boolean supported(Class<?> t) {
        return t == String.class
                || WRAPPERS.containsValue(t)
                || t == UUID.class
                || t == Instant.class
                || t == LocalDate.class
                || t.isEnum();
    }

    private static Object parse(String raw, Class<?> t) {
        if (t == String.class) {
            return raw;
        } else if (t == Integer.class) {
            return Integer.valueOf(raw);
        } else if (t == Long.class) {
            return Long.valueOf(raw);
        } else if (t == Short.class) {
            return Short.valueOf(raw);
        } else if (t == Byte.class) {
            return Byte.valueOf(raw);
        } else if (t == Boolean.class) {
            if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
                return Boolean.valueOf(raw);
            }
            throw new IllegalArgumentException(raw);
        } else if (t == Double.class) {
            return decimal(raw);
        } else if (t == Float.class) {
            float f = (float) decimal(raw);
            if (Float.isInfinite(f)) {
                throw new IllegalArgumentException(raw);
            }
            return f;
        } else if (t == UUID.class) {
            return UUID.fromString(raw);
        } else if (t == Instant.class) {
            return Instant.parse(raw);
        } else if (t == LocalDate.class) {
            return LocalDate.parse(raw);
        }
        for (Object c : t.getEnumConstants()) {
            if (((Enum<?>) c).name().equalsIgnoreCase(raw)) {
                return c;
            }
        }
        throw new IllegalArgumentException(raw);
    }

    private static double decimal(String raw) {
        if (!DECIMAL.matcher(raw).matches()) {
            throw new IllegalArgumentException(raw);
        }
        double d = Double.parseDouble(raw);
        if (Double.isInfinite(d)) {
            throw new IllegalArgumentException(raw);
        }
        return d;
    }
}
