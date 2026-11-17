package in.jolt;

import in.jolt.internal.http.Codec;
import in.jolt.internal.view.Views;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * An incoming HTTP request. Created by the server; public so tests can build one. A request is
 * handled by one thread at a time; its lazily parsed views ({@code query}, {@code form}, {@code
 * parts}) and attributes are not synchronized.
 */
public final class Request implements Params {

    private static final class Defaults {
        static final JoltConfig CONFIG = JoltConfig.defaults();
    }

    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, String> headers;
    private final byte[] body;
    private final JsonMapper mapper;
    private final Map<String, Object> attributes = new HashMap<>();
    private Map<String, String> params = Map.of();
    private Map<String, String> query;
    private Map<String, List<String>> queryAll;
    private Map<String, String> cookies;
    private Map<String, String> form;
    private final String remoteAddress;
    private App app;
    private final String scheme;
    private final String host;
    private final JoltConfig config;
    private List<Part> parts;

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
        this(method, path, rawQuery, headers, body, mapper, remoteAddress, null, null);
    }

    /** As above, with the scheme and host the server resolved; {@code null} means the defaults. */
    public Request(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            byte[] body,
            JsonMapper mapper,
            String remoteAddress,
            String scheme,
            String host) {
        this(method, path, rawQuery, headers, body, mapper, remoteAddress, scheme, host, null);
    }

    /**
     * As above, with the server settings that bound {@link #parts()}; {@code null} means {@link
     * JoltConfig#defaults()}.
     */
    public Request(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            byte[] body,
            JsonMapper mapper,
            String remoteAddress,
            String scheme,
            String host,
            JoltConfig config) {
        this.config = config;
        this.remoteAddress = remoteAddress;
        this.scheme = scheme;
        this.host = host;
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

    @Override
    public List<String> queryAll(String name) {
        if (queryAll == null) {
            Map<String, List<String>> all = new LinkedHashMap<>();
            eachPair(
                    rawQuery,
                    "&",
                    true,
                    false,
                    StandardCharsets.UTF_8,
                    (k, v) -> all.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
            all.replaceAll((k, v) -> Collections.unmodifiableList(v));
            queryAll = all;
        }
        return queryAll.getOrDefault(name, List.of());
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

    /**
     * Parts of a {@code multipart/form-data} body, in memory. Limits come from {@link
     * JoltConfig#maxParts} and {@link JoltConfig#maxPartBytes}.
     *
     * @throws HttpException 415 for another content type, 413 over a limit, 400 if malformed
     */
    public List<Part> parts() {
        String type = headers.getOrDefault("Content-Type", "");
        int semi = type.indexOf(';');
        String media = (semi < 0 ? type : type.substring(0, semi)).strip();
        if (!media.equalsIgnoreCase("multipart/form-data")) {
            throw new HttpException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected multipart/form-data");
        }
        if (parts == null) {
            parts = Multipart.parse(type, body, config == null ? Defaults.CONFIG : config);
        }
        return parts;
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

    String rawQuery() {
        return rawQuery;
    }

    Views views() {
        return app == null ? null : app.views();
    }

    void app(App app) {
        this.app = app;
    }

    /** IP address of the connected peer, or {@code null} when unknown. */
    public String remoteAddress() {
        return remoteAddress;
    }

    /** {@code "http"} or {@code "https"}: the forwarded scheme from a trusted proxy, else http. */
    public String scheme() {
        return scheme == null ? "http" : scheme;
    }

    /** The forwarded host from a trusted proxy, else the {@code Host} header, or {@code null}. */
    public String host() {
        return host == null ? headers.get("Host") : host;
    }

    /** The id set by {@link RequestId#middleware()}, or {@code null} when it is not installed. */
    public String id() {
        return (String) attributes.get(RequestId.ATTRIBUTE);
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
        eachPair(s, sep, plusAsSpace, strict, cs, out::putIfAbsent);
        return out;
    }

    private static void eachPair(
            String s,
            String sep,
            boolean plusAsSpace,
            boolean strict,
            Charset cs,
            BiConsumer<String, String> sink) {
        if (s.isEmpty()) {
            return;
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
            sink.accept(k, v);
        }
    }
}
