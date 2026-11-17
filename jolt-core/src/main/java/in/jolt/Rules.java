package in.jolt;

import java.math.BigDecimal;
import java.math.BigInteger;
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

    /** Inclusive bounds, compared exactly; throws IllegalArgumentException if min exceeds max. */
    public Rules range(String field, Number value, Number min, Number max) {
        if (compare(min, max) > 0) {
            throw new IllegalArgumentException("min " + min + " exceeds max " + max);
        }
        if (value == null) {
            return required(field);
        }
        boolean finite = !floating(value) || Double.isFinite(value.doubleValue());
        boolean ok = finite && compare(value, min) >= 0 && compare(value, max) <= 0;
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

    private static boolean floating(Number n) {
        return n instanceof Double || n instanceof Float;
    }

    private static int compare(Number a, Number b) {
        if (floating(a) && floating(b)) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return exact(a).compareTo(exact(b));
    }

    private static BigDecimal exact(Number n) {
        if (n instanceof BigDecimal d) {
            return d;
        }
        return n instanceof BigInteger i ? new BigDecimal(i) : new BigDecimal(n.toString());
    }
}
