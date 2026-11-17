package in.jolt.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * One MCP tool call, as sent to {@link McpOptions#audit}.
 *
 * @param agent identity from {@link McpOptions#agent}, or "anonymous"
 * @param client the caller's {@code User-Agent}, untrusted and truncated, or {@code null}
 * @param status HTTP status of the route, 429 when over budget, 400 for bad arguments
 * @param requestId {@code X-Request-Id} of the {@code /mcp} request, or {@code null}
 * @param arguments tool arguments when {@link McpOptions#auditArguments} is on, else {@code null}
 */
public record ToolCall(
        Instant at,
        String agent,
        String client,
        String tool,
        String method,
        String path,
        int status,
        Duration took,
        String requestId,
        Map<?, ?> arguments) {}
