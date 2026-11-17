package in.jolt.internal.json;

import java.lang.reflect.Array;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minimal JSON parser and writer. Parsed values are {@code Map}, {@code List}, {@code String},
 * {@code Long}, {@code Double}, {@code Boolean} or {@code null}.
 */
public final class Json {

    private static final Pattern NUMBER =
            Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final int MAX_DEPTH = 64;
    private static final int MAX_EXPONENT = 400;
    private static final Set<Class<?>> TEXT_TYPES =
            Set.of(
                    Instant.class,
                    LocalDate.class,
                    LocalDateTime.class,
                    LocalTime.class,
                    OffsetDateTime.class,
                    ZonedDateTime.class,
                    Duration.class,
                    UUID.class);
    private static final int UUID_LENGTH = 36;
    private static final String TOO_DEEP = "Body is nested too deeply (max " + MAX_DEPTH + ")";

    /**
     * A fractional or exponent number that keeps its text for exact BigDecimal/BigInteger targets.
     */
    private static final class Decimal extends Number {
        private final String text;
        private final double value;

        Decimal(String text, double value) {
            this.text = text;
            this.value = value;
        }

        @Override
        public int intValue() {
            return (int) value;
        }

        @Override
        public long longValue() {
            return (long) value;
        }

        @Override
        public float floatValue() {
            return (float) value;
        }

        @Override
        public double doubleValue() {
            return value;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final String src;
    private final boolean exact;
    private int pos;
    private int depth;

    private Json(String src, boolean exact) {
        this.src = src;
        this.exact = exact;
    }

    public static Object parse(String text) {
        return parse(text, false);
    }

    /**
     * Parses {@code text} and binds it to {@code type}. Unlike {@link #parse}, decimal text is kept
     * for {@code BigDecimal} and {@code BigInteger} targets; every other target sees {@code Double}
     * as usual.
     */
    public static <T> T read(String text, Class<T> type) {
        return bind(parse(text, true), type);
    }

    private static Object parse(String text, boolean exact) {
        Json p = new Json(text, exact);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error();
        }
        return v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static <T> T bind(Object parsed, Class<T> type) {
        return (T) convert(parsed, type, type.getSimpleName());
    }

    private IllegalArgumentException error() {
        return new IllegalArgumentException("Body is not valid JSON at char " + pos);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private Object value() {
        if (pos >= src.length()) {
            throw error();
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{', '[' -> {
                if (++depth > MAX_DEPTH) {
                    throw new IllegalArgumentException(TOO_DEEP);
                }
                Object nested = c == '{' ? object() : array();
                depth--;
                yield nested;
            }
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String word, Object result) {
        if (!src.startsWith(word, pos)) {
            throw error();
        }
        pos += word.length();
        return result;
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error();
            }
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            map.put(key, value());
            skipWs();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value());
            skipWs();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error();
        }
        pos++;
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                pos--;
                throw error();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                break;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error();
                    }
                    try {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error();
                    }
                    pos += 4;
                }
                default -> {
                    pos--;
                    throw error();
                }
            }
        }
        throw error();
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String s = src.substring(start, pos);
        if (!NUMBER.matcher(s).matches()) {
            pos = start;
            throw error();
        }
        try {
            if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                BigInteger big = new BigInteger(s);
                return big.bitLength() < 64 ? (Object) big.longValue() : big;
            }
            double d = Double.parseDouble(s);
            return exact ? new Decimal(s, d) : (Object) d;
        } catch (NumberFormatException e) {
            pos = start;
            throw error();
        }
    }

    private static void write(Object v, StringBuilder sb, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(TOO_DEEP);
        }
        switch (v) {
            case null -> sb.append("null");
            case CharSequence s -> quote(s, sb);
            case Character c -> quote(c.toString(), sb);
            case Enum<?> e -> quote(e.name(), sb);
            case Instant t -> quote(t.toString(), sb);
            case LocalDate t -> quote(t.toString(), sb);
            case LocalDateTime t -> quote(t.toString(), sb);
            case UUID t -> quote(t.toString(), sb);
            case LocalTime t -> quote(t.toString(), sb);
            case OffsetDateTime t -> quote(t.toString(), sb);
            case ZonedDateTime t -> quote(t.toString(), sb);
            case Duration t -> quote(t.toString(), sb);
            case Optional<?> o -> write(o.orElse(null), sb, depth);
            case Double d when d.isNaN() || d.isInfinite() -> sb.append("null");
            case Float f when f.isNaN() || f.isInfinite() -> sb.append("null");
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    quote(String.valueOf(en.getKey()), sb);
                    sb.append(':');
                    write(en.getValue(), sb, depth + 1);
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(o, sb, depth + 1);
                }
                sb.append(']');
            }
            case Record r -> writeRecord(r, sb, depth);
            default -> {
                if (!v.getClass().isArray()) {
                    throw new IllegalArgumentException(
                            "Cannot serialize " + v.getClass().getName() + " to JSON");
                }
                sb.append('[');
                for (int i = 0; i < Array.getLength(v); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(Array.get(v, i), sb, depth + 1);
                }
                sb.append(']');
            }
        }
    }

    private static void writeRecord(Record r, StringBuilder sb, int depth) {
        sb.append('{');
        boolean first = true;
        for (RecordComponent comp : r.getClass().getRecordComponents()) {
            Object value;
            try {
                var accessor = comp.getAccessor();
                accessor.setAccessible(true);
                value = accessor.invoke(r);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Cannot read " + comp.getName(), e);
            }
            if (value instanceof Optional<?> o && o.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(comp.getName(), sb);
            sb.append(':');
            write(value, sb, depth + 1);
        }
        sb.append('}');
    }

    private static void quote(CharSequence s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static Object convert(Object v, Type type, String where) {
        if (type instanceof WildcardType w) {
            return convert(v, w.getUpperBounds()[0], where);
        }
        if (type instanceof TypeVariable<?> tv) {
            return convert(v, tv.getBounds()[0], where);
        }
        Class<?> raw = rawClass(type);
        if (raw == Optional.class) {
            Type element =
                    type instanceof ParameterizedType pt
                            ? pt.getActualTypeArguments()[0]
                            : Object.class;
            return Optional.ofNullable(convert(v, element, where));
        }
        if (v instanceof Decimal d && raw != BigDecimal.class && raw != BigInteger.class) {
            v = d.doubleValue();
        }
        if (v == null) {
            return raw.isPrimitive() ? Array.get(Array.newInstance(raw, 1), 0) : null;
        }
        if (raw == Object.class) {
            return plain(v);
        }
        if (raw == String.class) {
            if (v instanceof Map || v instanceof List) {
                throw mismatch(where, "a string");
            }
            return v.toString();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (v instanceof Boolean) {
                return v;
            }
            throw mismatch(where, "a boolean");
        }
        if (Number.class.isAssignableFrom(wrap(raw))) {
            return number(v, wrap(raw), where);
        }
        if (TEXT_TYPES.contains(raw)) {
            return text(raw, v, where);
        }
        if (raw.isEnum()) {
            try {
                return enumValue(raw, v.toString());
            } catch (IllegalArgumentException e) {
                throw mismatch(where, "one of " + List.of(raw.getEnumConstants()));
            }
        }
        if (raw.isRecord() && v instanceof Map<?, ?> m) {
            return record(raw, m);
        }
        if (raw.isArray() && v instanceof List<?> list) {
            Type elem =
                    type instanceof GenericArrayType ga
                            ? ga.getGenericComponentType()
                            : raw.getComponentType();
            Object array = Array.newInstance(raw.getComponentType(), list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, convert(list.get(i), elem, where));
            }
            return array;
        }
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (v instanceof List<?> list
                    && args.length == 1
                    && Collection.class.isAssignableFrom(raw)) {
                Collection<Object> out = newCollection(raw);
                if (out != null) {
                    for (Object o : list) {
                        out.add(convert(o, args[0], where));
                    }
                    return out;
                }
            } else if (v instanceof Map<?, ?> map
                    && args.length == 2
                    && Map.class.isAssignableFrom(raw)
                    && raw.isAssignableFrom(LinkedHashMap.class)) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach(
                        (k, o) -> out.put(String.valueOf(k), convert(o, args[1], where + "." + k)));
                return out;
            }
        }
        if (raw.isInstance(v)) {
            return plain(v);
        }
        throw mismatch(where, raw.getSimpleName());
    }

    private static Object text(Class<?> raw, Object v, String where) {
        if (!(v instanceof String s)) {
            throw mismatch(where, "a string");
        }
        try {
            if (raw == Instant.class) {
                return Instant.parse(s);
            } else if (raw == LocalDate.class) {
                return LocalDate.parse(s);
            } else if (raw == LocalDateTime.class) {
                return LocalDateTime.parse(s);
            } else if (raw == LocalTime.class) {
                return LocalTime.parse(s);
            } else if (raw == OffsetDateTime.class) {
                return OffsetDateTime.parse(s);
            } else if (raw == ZonedDateTime.class) {
                return ZonedDateTime.parse(s);
            } else if (raw == Duration.class) {
                return Duration.parse(s);
            } else if (s.length() == UUID_LENGTH) {
                return UUID.fromString(s);
            }
        } catch (RuntimeException e) {
            // reported below
        }
        throw mismatch(where, "a valid " + raw.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Object plain(Object v) {
        if (v instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).replaceAll((k, x) -> plain(x));
        } else if (v instanceof List<?> l) {
            ((List<Object>) l).replaceAll(Json::plain);
        } else if (v instanceof Decimal d) {
            return d.doubleValue();
        }
        return v;
    }

    private static Collection<Object> newCollection(Class<?> raw) {
        if (raw.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        return raw.isAssignableFrom(LinkedHashSet.class) ? new LinkedHashSet<>() : null;
    }

    private static Class<?> rawClass(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> rawClass(p.getRawType());
            case WildcardType w -> rawClass(w.getUpperBounds()[0]);
            case TypeVariable<?> tv -> rawClass(tv.getBounds()[0]);
            case GenericArrayType ga ->
                    Array.newInstance(rawClass(ga.getGenericComponentType()), 0).getClass();
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> raw, String name) {
        return Enum.valueOf((Class<? extends Enum>) raw, name);
    }

    private static Object record(Class<?> raw, Map<?, ?> m) {
        RecordComponent[] comps = raw.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            args[i] =
                    convert(
                            m.get(comps[i].getName()),
                            comps[i].getGenericType(),
                            raw.getSimpleName() + "." + comps[i].getName());
        }
        try {
            var ctor = raw.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalArgumentException(
                    raw.getSimpleName() + ": " + e.getCause().getMessage(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot construct " + raw.getSimpleName(), e);
        }
    }

    private static Object number(Object v, Class<?> target, String where) {
        if (!(v instanceof Number n)) {
            throw mismatch(where, "a number");
        }
        if (target == Double.class) {
            return n.doubleValue();
        } else if (target == Float.class) {
            return n.floatValue();
        }
        if (n instanceof Double d && !Double.isFinite(d)) {
            throw mismatch(where, "a finite number");
        }
        if ((target == BigDecimal.class || target == BigInteger.class) && n instanceof Decimal) {
            checkExponent(n.toString(), where);
        }
        BigDecimal exact = new BigDecimal(n.toString());
        try {
            if (target == Integer.class) {
                return exact.intValueExact();
            } else if (target == Long.class) {
                return exact.longValueExact();
            } else if (target == Short.class) {
                return exact.shortValueExact();
            } else if (target == Byte.class) {
                return exact.byteValueExact();
            } else if (target == BigInteger.class) {
                return exact.toBigIntegerExact();
            }
        } catch (ArithmeticException e) {
            throw mismatch(where, "an integer in range for " + target.getSimpleName());
        }
        return target == BigDecimal.class ? exact : n;
    }

    private static void checkExponent(String text, String where) {
        int e = Math.max(text.indexOf('e'), text.indexOf('E'));
        if (e < 0) {
            return;
        }
        String digits = text.substring(e + 1).replace("+", "").replace("-", "");
        if (digits.length() > 4 || Integer.parseInt(digits) > MAX_EXPONENT) {
            throw new IllegalArgumentException(
                    "Exponent out of range (max " + MAX_EXPONENT + ") for " + where);
        }
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        return Array.get(Array.newInstance(c, 1), 0).getClass();
    }

    private static IllegalArgumentException mismatch(String where, String expected) {
        return new IllegalArgumentException("Expected " + expected + " for " + where);
    }
}
