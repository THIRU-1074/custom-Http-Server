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
        JsonMapper jsonMapper) {

    public static JoltConfig defaults() {
        return new JoltConfig(
                1000,
                16 * 1024,
                1024 * 1024,
                100,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                new BuiltInJsonMapper());
    }

    public JoltConfig maxConnections(int v) {
        return new JoltConfig(
                v,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper);
    }

    public JoltConfig maxHeaderBytes(int v) {
        return new JoltConfig(
                maxConnections,
                v,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper);
    }

    public JoltConfig maxBodyBytes(int v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                v,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                jsonMapper);
    }

    public JoltConfig maxRequestsPerConnection(int v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                v,
                readTimeout,
                keepAliveTimeout,
                jsonMapper);
    }

    public JoltConfig readTimeout(Duration v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                v,
                keepAliveTimeout,
                jsonMapper);
    }

    public JoltConfig keepAliveTimeout(Duration v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                v,
                jsonMapper);
    }

    public JoltConfig jsonMapper(JsonMapper v) {
        return new JoltConfig(
                maxConnections,
                maxHeaderBytes,
                maxBodyBytes,
                maxRequestsPerConnection,
                readTimeout,
                keepAliveTimeout,
                v);
    }
}
