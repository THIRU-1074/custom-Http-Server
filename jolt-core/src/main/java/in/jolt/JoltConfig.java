package in.jolt;

import in.jolt.internal.json.BuiltInJsonMapper;
import java.time.Duration;

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
        String bindAddress) {

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
                "127.0.0.1");
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                bindAddress);
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
                v);
    }
}
