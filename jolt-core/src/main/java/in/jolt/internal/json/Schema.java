package in.jolt.internal.json;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JSON Schema (2020-12) for the types {@link Json#bind} accepts. Unsupported or recursive types
 * become {@code {}} (any value).
 */
public final class Schema {

    private static final Map<Class<?>, String> FORMATS =
            Map.of(
                    UUID.class, "uuid",
                    LocalDate.class, "date",
                    LocalTime.class, "time",
                    LocalDateTime.class, "date-time",
                    Instant.class, "date-time",
                    OffsetDateTime.class, "date-time",
                    ZonedDateTime.class, "date-time",
                    Duration.class, "duration");
    private static final Set<Class<?>> INTEGERS =
            Set.of(
                    int.class,
                    Integer.class,
                    long.class,
                    Long.class,
                    short.class,
                    Short.class,
                    byte.class,
                    Byte.class,
                    BigInteger.class);
    private static final Set<Class<?>> NUMBERS =
            Set.of(double.class, Double.class, float.class, Float.class, BigDecimal.class);

    private Schema() {}

    public static Map<String, Object> of(Type type) {
        return of(type, new HashSet<>());
    }

    private static Map<String, Object> of(Type type, Set<Class<?>> seen) {
        if (type instanceof WildcardType w) {
            return of(w.getUpperBounds()[0], seen);
        }
        if (type instanceof TypeVariable<?>) {
            return new LinkedHashMap<>();
        }
        if (type instanceof GenericArrayType ga) {
            return array(of(ga.getGenericComponentType(), seen));
        }
        Class<?> raw = raw(type);
        if (raw == null || raw == Object.class) {
            return new LinkedHashMap<>();
        }
        if (raw == Optional.class) {
            return of(arg(type, 0), seen);
        }
        if (raw == String.class) {
            return typed("string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return typed("boolean");
        }
        if (INTEGERS.contains(raw)) {
            return typed("integer");
        }
        if (NUMBERS.contains(raw)) {
            return typed("number");
        }
        if (FORMATS.containsKey(raw)) {
            Map<String, Object> s = typed("string");
            s.put("format", FORMATS.get(raw));
            return s;
        }
        if (raw.isEnum()) {
            Map<String, Object> s = typed("string");
            List<String> names = new ArrayList<>();
            for (Object c : raw.getEnumConstants()) {
                names.add(((Enum<?>) c).name());
            }
            s.put("enum", names);
            return s;
        }
        if (raw.isArray()) {
            return array(of(raw.getComponentType(), seen));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return array(of(arg(type, 0), seen));
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<String, Object> s = typed("object");
            s.put("additionalProperties", of(arg(type, 1), seen));
            return s;
        }
        if (raw.isRecord() && seen.add(raw)) {
            try {
                return record(raw, seen);
            } finally {
                seen.remove(raw);
            }
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> record(Class<?> raw, Set<Class<?>> seen) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent c : raw.getRecordComponents()) {
            props.put(c.getName(), of(c.getGenericType(), seen));
            if (c.getType() != Optional.class) {
                required.add(c.getName());
            }
        }
        Map<String, Object> s = typed("object");
        s.put("properties", props);
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        return s;
    }

    private static Map<String, Object> typed(String name) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", name);
        return s;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> s = typed("array");
        s.put("items", items);
        return s;
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    private static Type arg(Type type, int i) {
        if (type instanceof ParameterizedType pt && pt.getActualTypeArguments().length > i) {
            return pt.getActualTypeArguments()[i];
        }
        return Object.class;
    }
}
