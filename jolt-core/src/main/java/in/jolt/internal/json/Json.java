package in.jolt.internal.json;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and writer. Parsed values are {@code Map}, {@code List}, {@code String},
 * {@code Long}, {@code Double}, {@code Boolean} or {@code null}.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
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
        write(value, sb);
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
            case '{' -> object();
            case '[' -> array();
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
        try {
            if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                return Long.parseLong(s);
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            pos = start;
            throw error();
        }
    }

    private static void write(Object v, StringBuilder sb) {
        switch (v) {
            case null -> sb.append("null");
            case CharSequence s -> quote(s, sb);
            case Character c -> quote(c.toString(), sb);
            case Enum<?> e -> quote(e.name(), sb);
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
                    write(en.getValue(), sb);
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
                    write(o, sb);
                }
                sb.append(']');
            }
            case Record r -> writeRecord(r, sb);
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
                    write(Array.get(v, i), sb);
                }
                sb.append(']');
            }
        }
    }

    private static void writeRecord(Record r, StringBuilder sb) {
        sb.append('{');
        RecordComponent[] comps = r.getClass().getRecordComponents();
        for (int i = 0; i < comps.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            quote(comps[i].getName(), sb);
            sb.append(':');
            try {
                var accessor = comps[i].getAccessor();
                accessor.setAccessible(true);
                write(accessor.invoke(r), sb);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Cannot read " + comps[i].getName(), e);
            }
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
        Class<?> raw =
                type instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() : (Class<?>) type;
        if (v == null) {
            return raw.isPrimitive() ? Array.get(Array.newInstance(raw, 1), 0) : null;
        }
        if (raw == Object.class) {
            return v;
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
        if (type instanceof ParameterizedType pt && v instanceof List<?> list) {
            Type elem = pt.getActualTypeArguments()[0];
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                out.add(convert(o, elem, where));
            }
            return out;
        }
        if (type instanceof ParameterizedType pt && v instanceof Map<?, ?> map) {
            Type elem = pt.getActualTypeArguments()[1];
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, o) -> out.put(String.valueOf(k), convert(o, elem, where + "." + k)));
            return out;
        }
        if (raw.isInstance(v)) {
            return v;
        }
        throw mismatch(where, raw.getSimpleName());
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
        if (target == Integer.class) {
            return n.intValue();
        } else if (target == Long.class) {
            return n.longValue();
        } else if (target == Double.class) {
            return n.doubleValue();
        } else if (target == Float.class) {
            return n.floatValue();
        } else if (target == Short.class) {
            return n.shortValue();
        } else if (target == Byte.class) {
            return n.byteValue();
        } else if (target == BigDecimal.class) {
            return new BigDecimal(n.toString());
        }
        return n;
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
