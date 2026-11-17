package in.jolt;

import in.jolt.internal.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small key/value session kept in a signed cookie; see {@link Sessions}. */
public final class Session {

    static final int MAX_BYTES = 3072;

    private final Map<String, Object> data;
    private final boolean fresh;
    private boolean changed;

    Session() {
        this(new LinkedHashMap<>(), true);
    }

    Session(Map<String, Object> data, boolean fresh) {
        this.data = data;
        this.fresh = fresh;
    }

    public Object get(String key) {
        return data.get(key);
    }

    /**
     * Stores a string, finite number or boolean.
     *
     * @throws IllegalArgumentException for any other value
     * @throws IllegalStateException if the encoded session would exceed 3072 bytes
     */
    public Session put(String key, Object value) {
        boolean ok =
                value instanceof String
                        || value instanceof Boolean
                        || value instanceof Integer
                        || value instanceof Long
                        || value instanceof Short
                        || value instanceof Byte
                        || value instanceof Double d && Double.isFinite(d)
                        || value instanceof Float f && Float.isFinite(f);
        if (!ok) {
            throw new IllegalArgumentException(
                    "Session values must be strings, finite numbers or booleans");
        }
        Object previous = data.put(key, value);
        if ((serialised().length + 2) / 3 * 4 > MAX_BYTES) {
            if (previous == null) {
                data.remove(key);
            } else {
                data.put(key, previous);
            }
            throw new IllegalStateException(
                    "Session exceeds " + MAX_BYTES + " bytes; store less data in the cookie");
        }
        changed = true;
        return this;
    }

    public Session remove(String key) {
        changed |= data.remove(key) != null;
        return this;
    }

    public Session clear() {
        changed |= !data.isEmpty();
        data.clear();
        return this;
    }

    /** True when the request carried no valid session cookie. */
    public boolean isNew() {
        return fresh;
    }

    boolean changed() {
        return changed;
    }

    boolean isEmpty() {
        return data.isEmpty();
    }

    Map<String, Object> data() {
        return data;
    }

    byte[] serialised() {
        return Json.write(data).getBytes(StandardCharsets.UTF_8);
    }
}
