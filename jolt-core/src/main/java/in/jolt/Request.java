package in.jolt;

import in.jolt.internal.http.Codec;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** An incoming HTTP request. Created by the server; public so tests can build one. */
public final class Request {

    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, String> headers;
    private final byte[] body;
    private final JsonMapper mapper;
    private final Map<String, Object> attributes = new HashMap<>();
    private Map<String, String> params = Map.of();
    private Map<String, String> query;
    private Map<String, String> cookies;

    public Request(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            byte[] body,
            JsonMapper mapper) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery == null ? "" : rawQuery;
        this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        this.headers.putAll(headers);
        this.body = body == null ? new byte[0] : body;
        this.mapper = mapper;
    }

    public String method() {
        return method;
    }

    /** Percent-decoded path without the query string. */
    public String path() {
        return path;
    }

    /** Case-insensitive, read-only view of the headers. */
    public Map<String, String> headers() {
        return Collections.unmodifiableMap(headers);
    }

    public String header(String name) {
        return headers.get(name);
    }

    /** Value of a {@code :name} path parameter, or {@code null}. */
    public String param(String name) {
        return params.get(name);
    }

    /** First value of a query parameter, or {@code null}. */
    public String query(String name) {
        return query().get(name);
    }

    public Map<String, String> query() {
        if (query == null) {
            query = parsePairs(rawQuery, "&", true);
        }
        return Collections.unmodifiableMap(query);
    }

    /** Value of a cookie from the {@code Cookie} header, or {@code null}. */
    public String cookie(String name) {
        if (cookies == null) {
            cookies = parsePairs(headers.getOrDefault("Cookie", ""), ";", false);
        }
        return cookies.get(name);
    }

    public byte[] body() {
        return body;
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Binds the body to {@code type}: JSON for records, maps and lists, raw for {@code byte[]} and
     * {@code String}.
     *
     * @throws HttpException 400 if the body is not valid for the type
     */
    @SuppressWarnings("unchecked")
    public <T> T body(Class<T> type) {
        if (type == byte[].class) {
            return (T) body;
        }
        if (type == String.class) {
            return (T) text();
        }
        if (body.length == 0) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Body is empty");
        }
        try {
            return mapper.read(body, type);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Per-request value set by middleware, or {@code null}. */
    public Object attr(String name) {
        return attributes.get(name);
    }

    public Request attr(String name, Object value) {
        attributes.put(name, value);
        return this;
    }

    void params(Map<String, String> params) {
        this.params = params;
    }

    private static Map<String, String> parsePairs(String s, String sep, boolean plusAsSpace) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s.isEmpty()) {
            return out;
        }
        for (String pair : s.split(sep)) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                k = Codec.percentDecode(k, plusAsSpace);
                v = Codec.percentDecode(v, plusAsSpace);
            } catch (IllegalArgumentException ignored) {
                // keep the raw token when the escape is malformed
            }
            out.putIfAbsent(k, v);
        }
        return out;
    }
}
