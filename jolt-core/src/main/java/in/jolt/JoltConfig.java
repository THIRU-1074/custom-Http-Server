package in.jolt;

import in.jolt.internal.http.Tls;
import in.jolt.internal.http.TrustedProxies;
import in.jolt.internal.json.BuiltInJsonMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;

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
        boolean problemJson,
        SSLContext tls,
        Duration streamIdleTimeout,
        Duration streamMaxDuration,
        int wsMaxFrameBytes,
        int wsMaxMessageBytes) {

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
                false,
                null,
                Duration.ofSeconds(60),
                Duration.ofHours(1),
                64 * 1024,
                1024 * 1024);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
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
                v,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
    }

    /**
     * Serves HTTPS with {@code context} (TLS 1.2 and 1.3, forward-secret AEAD suites only).
     * Default: plain HTTP; a reverse proxy is still the usual production route.
     */
    public JoltConfig tls(SSLContext context) {
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
                problemJson,
                context,
                streamIdleTimeout,
                streamMaxDuration,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
    }

    /**
     * Serves HTTPS with the key and certificate chain from a PKCS12 keystore.
     *
     * @throws IllegalArgumentException if the keystore cannot be loaded with that password
     */
    public JoltConfig tls(Path keystore, char[] password) {
        return tls(Tls.context(keystore, password));
    }

    /**
     * Limits for streamed responses and WebSocket connections. A stream with no write for {@code
     * idle} is closed; a WebSocket is pinged after {@code idle} without a frame and dropped after
     * twice that. A connection older than {@code max} is closed; zero means no cap. Defaults: 60
     * seconds and one hour.
     *
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code idle} is not positive or {@code max} is negative
     */
    public JoltConfig streamTimeouts(Duration idle, Duration max) {
        Objects.requireNonNull(idle, "idle");
        Objects.requireNonNull(max, "max");
        if (idle.isZero() || idle.isNegative()) {
            throw new IllegalArgumentException("idle must be positive");
        }
        if (max.isNegative()) {
            throw new IllegalArgumentException("max must not be negative");
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
                maxParts,
                trustedProxies,
                problemJson,
                tls,
                idle,
                max,
                wsMaxFrameBytes,
                wsMaxMessageBytes);
    }

    /**
     * Largest accepted WebSocket frame and message (fragments added up), in bytes; larger ones
     * close the connection with code 1009. Defaults 64 KB and 1 MB.
     *
     * @throws IllegalArgumentException if either limit is not positive
     */
    public JoltConfig wsLimits(int maxFrameBytes, int maxMessageBytes) {
        if (maxFrameBytes <= 0 || maxMessageBytes <= 0) {
            throw new IllegalArgumentException("WebSocket limits must be positive");
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
                maxParts,
                trustedProxies,
                problemJson,
                tls,
                streamIdleTimeout,
                streamMaxDuration,
                maxFrameBytes,
                maxMessageBytes);
    }
}
