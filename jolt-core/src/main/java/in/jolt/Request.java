package in.jolt;

import in.jolt.internal.http.Codec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** An incoming HTTP request. Created by the server; public so tests can build one. */
public final class Request implements Params {

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
    private Map<String, String> form;
    private final String remoteAddress;
    private App app;

    public Request(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            byte[] body,
            JsonMapper mapper) {
        this(method, path, rawQuery, headers, body, mapper, null);
    }

    /** As above, with the peer address the server saw; it cannot change afterwards. */
    public Request(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            byte[] body,
            JsonMapper mapper,
            String remoteAddress) {
        this.remoteAddress = remoteAddress;
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

    @Override
    public String param(String name) {
        return params.get(name);
    }

    @Override
    public String query(String name) {
        return query().get(name);
    }

    public Map<String, String> query() {
        if (query == null) {
            query = parsePairs(rawQuery, "&", true, false);
        }
        return Collections.unmodifiableMap(query);
    }

    /**
     * Fields of an {@code application/x-www-form-urlencoded} body (first value wins), or an empty
     * map for any other media type. Honours a {@code charset} parameter, default UTF-8. The body is
     * already capped by {@code maxBodyBytes}.
     *
     * @throws HttpException 400 on a malformed percent-escape, 415 on an unsupported charset
     */
    public Map<String, String> form() {
        String type = headers.getOrDefault("Content-Type", "");
        int semi = type.indexOf(';');
        String media = (semi < 0 ? type : type.substring(0, semi)).strip();
        if (!media.equalsIgnoreCase("application/x-www-form-urlencoded")) {
            return Map.of();
        }
        if (form == null) {
            Charset cs = charset(semi < 0 ? "" : type.substring(semi + 1));
            form = parsePairs(new String(body, cs), "&", true, true, cs);
        }
        return Collections.unmodifiableMap(form);
    }

    private static Charset charset(String params) {
        for (String param : params.split(";")) {
            int eq = param.indexOf('=');
            if (eq > 0 && param.substring(0, eq).strip().equalsIgnoreCase("charset")) {
                String name = param.substring(eq + 1).strip();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    throw new HttpException(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported charset");
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Value of a cookie from the {@code Cookie} header, or {@code null}. */
    public String cookie(String name) {
        if (cookies == null) {
            cookies = parsePairs(headers.getOrDefault("Cookie", ""), ";", false, false);
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
            BodyCodec codec = codec();
            return codec == null ? mapper.read(body, type) : codec.read(body, type);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private BodyCodec codec() {
        List<BodyCodec> codecs = codecs();
        if (codecs.isEmpty()) {
            return null;
        }
        String type = headers.getOrDefault("Content-Type", "");
        int semi = type.indexOf(';');
        String media = (semi < 0 ? type : type.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
        for (BodyCodec c : codecs) {
            if (c.mediaType().equalsIgnoreCase(media)) {
                return c;
            }
        }
        if (media.isEmpty() || media.equals("application/json") || media.endsWith("+json")) {
            return null;
        }
        throw new HttpException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
    }

    List<BodyCodec> codecs() {
        return app == null ? List.of() : app.codecs();
    }

    void app(App app) {
        this.app = app;
    }

    /** IP address of the connected peer, or {@code null} when unknown. */
    public String remoteAddress() {
        return remoteAddress;
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

    private static Map<String, String> parsePairs(
            String s, String sep, boolean plusAsSpace, boolean strict) {
        return parsePairs(s, sep, plusAsSpace, strict, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parsePairs(
            String s, String sep, boolean plusAsSpace, boolean strict, Charset cs) {
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
                k = Codec.percentDecode(k, plusAsSpace, cs);
                v = Codec.percentDecode(v, plusAsSpace, cs);
            } catch (IllegalArgumentException e) {
                if (strict) {
                    throw new HttpException(HttpStatus.BAD_REQUEST, "Malformed form encoding");
                }
            }
            out.putIfAbsent(k, v);
        }
        return out;
    }
}
