package in.jolt;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Collects field errors. Each rule records a message for {@code field} and returns this; the first
 * message per field wins. A {@code null} value fails every rule except {@link #error}.
 */
public final class Rules {

    private final Map<String, String> errors = new LinkedHashMap<>();

    /** Records {@code message} for {@code field} unless it already has one. */
    public Rules error(String field, String message) {
        errors.putIfAbsent(field, message);
        return this;
    }

    public Rules notBlank(String field, String value) {
        return value == null || value.isBlank() ? error(field, "must not be blank") : this;
    }

    /** Inclusive bounds; integral values compare as longs, others as doubles. */
    public Rules range(String field, Number value, Number min, Number max) {
        if (value == null) {
            return required(field);
        }
        boolean integral = integral(value) && integral(min) && integral(max);
        boolean ok =
                integral
                        ? value.longValue() >= min.longValue()
                                && value.longValue() <= max.longValue()
                        : value.doubleValue() >= min.doubleValue()
                                && value.doubleValue() <= max.doubleValue();
        return ok ? this : error(field, "must be between " + min + " and " + max);
    }

    /** Length of a string, inclusive bounds. */
    public Rules size(String field, String value, int min, int max) {
        return value == null ? required(field) : size(field, value.length(), min, max);
    }

    /** Number of elements, inclusive bounds. */
    public Rules size(String field, Collection<?> value, int min, int max) {
        return value == null ? required(field) : size(field, value.size(), min, max);
    }

    /** The whole value must match {@code pattern}. */
    public Rules matches(String field, String value, Pattern pattern) {
        if (value == null) {
            return required(field);
        }
        return pattern.matcher(value).matches() ? this : error(field, "has an invalid format");
    }

    public Rules oneOf(String field, Object value, Object... allowed) {
        if (value == null) {
            return required(field);
        }
        for (Object a : allowed) {
            if (Objects.equals(a, value)) {
                return this;
            }
        }
        StringBuilder sb = new StringBuilder("must be one of: ");
        for (int i = 0; i < allowed.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(allowed[i]);
        }
        return error(field, sb.toString());
    }

    /** Errors so far, in the order first recorded; read-only. */
    public Map<String, String> errors() {
        return Collections.unmodifiableMap(errors);
    }

    private Rules size(String field, int n, int min, int max) {
        return n >= min && n <= max
                ? this
                : error(field, "size must be between " + min + " and " + max);
    }

    private Rules required(String field) {
        return error(field, "is required");
    }

    private static boolean integral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }
}
