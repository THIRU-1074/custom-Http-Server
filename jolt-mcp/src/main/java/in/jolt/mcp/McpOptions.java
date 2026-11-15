package in.jolt.mcp;

import in.jolt.Middleware;
import java.util.Set;

/**
 * MCP endpoint settings.
 *
 * @param allowedOrigins {@code Origin} values accepted; requests without an Origin always pass
 * @param auth optional middleware run before the endpoint, or {@code null}
 * @param name server name reported in {@code initialize}
 */
public record McpOptions(Set<String> allowedOrigins, Middleware auth, String name) {

    public static McpOptions defaults() {
        return new McpOptions(Set.of(), null, "jolt");
    }

    /** Browsers send Origin; anything not listed here is rejected with 403. */
    public McpOptions allowOrigins(String... origins) {
        return new McpOptions(Set.of(origins), auth, name);
    }

    public McpOptions auth(Middleware mw) {
        return new McpOptions(allowedOrigins, mw, name);
    }

    public McpOptions name(String serverName) {
        return new McpOptions(allowedOrigins, auth, serverName);
    }
}
