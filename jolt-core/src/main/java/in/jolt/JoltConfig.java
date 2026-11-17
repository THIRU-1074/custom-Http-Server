package in.jolt;

import in.jolt.internal.json.BuiltInJsonMapper;
import java.time.Duration;
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
                v);
    }
}
