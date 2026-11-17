package in.jolt;

import in.jolt.internal.http.TrustedProxies;
import in.jolt.internal.json.BuiltInJsonMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable server settings. Start from {@link #defaults()} and override with the fluent copies.
 */
public record JoltConfig(
        int maxConnections,
        int maxHeaderBytes,
        int maxBodyBytes,
        int maxRequestsPerConnection,
        Duration readTimeout,
        Duration keepAliveTimeout,
        JsonMapper jsonMapper,
        int maxRequestLineBytes,
        int maxHeaderCount,
        boolean secureCookies,
        long maxStaticFileBytes,
        String bindAddress,
        int maxPartBytes,
        int maxParts,
        List<String> trustedProxies,
        boolean problemJson) {

    public static JoltConfig defaults() {
        return new JoltConfig(
                1000,
                16 * 1024,
                1024 * 1024,
                100,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                new BuiltInJsonMapper(),
                8 * 1024,
                100,
                false,
                10L * 1024 * 1024,
                "127.0.0.1",
                1024 * 1024,
                10,
                List.of(),
                false);
    }

    private static final System.Logger LOG = System.getLogger("in.jolt");

    /**
     * Defaults overridden by {@code JOLT_BIND_ADDRESS}, {@code JOLT_MAX_BODY_BYTES} and {@code
     * JOLT_MAX_CONNECTIONS} when set; blank counts as unset. The old {@code BIND_ADDRESS} is read
     * only when {@code JOLT_BIND_ADDRESS} is unset, with a warning.
     *
     * @throws IllegalArgumentException naming the variable if a value is invalid
     */
    public static JoltConfig fromEnv() {
        return fromEnv(System.getenv(), LOG);
    }

    static JoltConfig fromEnv(Map<String, String> env) {
        return fromEnv(env, LOG);
    }

    static JoltConfig fromEnv(Map<String, String> env, System.Logger log) {
        JoltConfig c = defaults();
        String bind = value(env, "JOLT_BIND_ADDRESS");
        if (bind == null) {
            bind = value(env, "BIND_ADDRESS");
            if (bind != null) {
                log.log(
                        System.Logger.Level.WARNING,
                        "BIND_ADDRESS is deprecated, use JOLT_BIND_ADDRESS");
            }
        }
        if (bind != null) {
            c = c.bindAddress(bind);
        }
        return c.maxBodyBytes(positiveInt(env, "JOLT_MAX_BODY_BYTES", c.maxBodyBytes()))
                .maxConnections(positiveInt(env, "JOLT_MAX_CONNECTIONS", c.maxConnections()));
    }

    private static String value(Map<String, String> env, String name) {
        String raw = env.get(name);
        return raw == null || raw.isBlank() ? null : raw.strip();
    }

    private static int positiveInt(Map<String, String> env, String name, int fallback) {
        String raw = value(env, name);
        if (raw == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw);
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException(name + " must be a positive integer, got: " + raw);
    }

    public JoltConfig maxConnections(int v) {
        return new JoltConfig(
                v,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig maxHeaderBytes(int v) {
        return new JoltConfig(
                maxConnections,
                v,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig maxBodyBytes(int v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                v,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig maxRequestsPerConnection(int v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                v,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig readTimeout(Duration v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                v,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig keepAliveTimeout(Duration v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                v,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig jsonMapper(JsonMapper v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                v,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig maxRequestLineBytes(int v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                v,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig maxHeaderCount(int v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                v,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig secureCookies(boolean v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                v,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    public JoltConfig maxStaticFileBytes(long v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                v,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    /**
     * Local address the server binds to. Defaults to loopback; pass {@code "0.0.0.0"} to accept
     * connections from other hosts.
     */
    public JoltConfig bindAddress(String v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                v,
                maxPartBytes,
                maxParts,
                trustedProxies,
                problemJson);
    }

    /**
     * Largest accepted multipart part, in bytes. Defaults to 1 MB; larger parts get 413.
     *
     * @throws IllegalArgumentException if {@code v} is not positive
     */
    public JoltConfig maxPartBytes(int v) {
        if (v <= 0) {
            throw new IllegalArgumentException("maxPartBytes must be positive, got: " + v);
        }
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                v,
                maxParts,
                trustedProxies,
                problemJson);
    }

    /**
     * Most parts accepted in one multipart body. Defaults to 10; more get 413.
     *
     * @throws IllegalArgumentException if {@code v} is not positive
     */
    public JoltConfig maxParts(int v) {
        if (v <= 0) {
            throw new IllegalArgumentException("maxParts must be positive, got: " + v);
        }
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                v,
                trustedProxies,
                problemJson);
    }

    /**
     * Peers (IP addresses or CIDR blocks such as {@code 10.0.0.0/8}) whose {@code X-Forwarded-For},
     * {@code X-Forwarded-Proto} and {@code X-Forwarded-Host} headers are honoured. Default: none.
     *
     * @throws IllegalArgumentException if an entry is not an IP literal or CIDR block
     */
    public JoltConfig trustProxy(String... cidrsOrAddresses) {
        List<String> specs = List.of(cidrsOrAddresses);
        TrustedProxies.of(specs);
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                specs,
                problemJson);
    }

    /**
     * Answers errors as {@code application/problem+json} (RFC 9457: type, title, status, detail,
     * plus {@code errors} for validation) instead of plain text. Off by default.
     */
    public JoltConfig problemJson(boolean v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper,
                maxRequestLineBytes,
                maxHeaderCount,
                secureCookies,
                maxStaticFileBytes,
                bindAddress,
                maxPartBytes,
                maxParts,
                trustedProxies,
                v);
    }
}
