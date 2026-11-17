package in.jolt.mcp;

import in.jolt.Middleware;
import in.jolt.Request;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * MCP endpoint settings.
 *
 * @param allowedOrigins {@code Origin} values accepted; requests without an Origin always pass
 * @param auth optional middleware run before the endpoint, or {@code null}
 * @param name server name reported in {@code initialize}
 * @param agent trusted caller identity from the {@code /mcp} request, or {@code null} for
 *     "anonymous"
 * @param audit receives every tool call, or {@code null} to log one INFO line per call
 * @param auditArguments whether {@link ToolCall#arguments()} is filled; off because arguments may
 *     hold secrets
 * @param budgets per-agent call limits
 */
public record McpOptions(
        Set<String> allowedOrigins,
        Middleware auth,
        String name,
        Function<Request, String> agent,
        Consumer<ToolCall> audit,
        boolean auditArguments,
        List<Budget> budgets) {

    /**
     * At most {@code calls} calls of {@code tool} ({@code "*"} for all tools) per agent in each
     * window.
     */
    public record Budget(String tool, int calls, Duration window) {}

    public McpOptions {
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        budgets = budgets == null ? List.of() : List.copyOf(budgets);
    }

    public static McpOptions defaults() {
        return new McpOptions(Set.of(), null, "jolt", null, null, false, List.of());
    }

    /** Browsers send Origin; anything not listed here is rejected with 403. */
    public McpOptions allowOrigins(String... origins) {
        return new McpOptions(Set.of(origins), auth, name, agent, audit, auditArguments, budgets);
    }

    public McpOptions auth(Middleware mw) {
        return new McpOptions(allowedOrigins, mw, name, agent, audit, auditArguments, budgets);
    }

    public McpOptions name(String serverName) {
        return new McpOptions(
                allowedOrigins, auth, serverName, agent, audit, auditArguments, budgets);
    }

    /**
     * Names the caller, e.g. {@code req -> (String) req.attr("user")} after auth; null is
     * "anonymous".
     */
    public McpOptions agent(Function<Request, String> fn) {
        return new McpOptions(allowedOrigins, auth, name, fn, audit, auditArguments, budgets);
    }

    /** Sends each tool call to {@code sink} instead of the default log line. */
    public McpOptions audit(Consumer<ToolCall> sink) {
        return new McpOptions(allowedOrigins, auth, name, agent, sink, auditArguments, budgets);
    }

    /** Includes tool arguments in audit records (and the default log line). */
    public McpOptions auditArguments(boolean include) {
        return new McpOptions(allowedOrigins, auth, name, agent, audit, include, budgets);
    }

    /**
     * Limits each agent to {@code calls} calls of {@code tool} per {@code window}; {@code "*"}
     * counts every tool. Over budget, the call returns an error result naming the retry delay.
     */
    public McpOptions budget(String tool, int calls, Duration window) {
        if (tool == null || tool.isEmpty()) {
            throw new IllegalArgumentException("budget tool name is required; use \"*\" for all");
        }
        if (calls < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("budget needs calls >= 1 and a positive window");
        }
        List<Budget> next = new ArrayList<>(budgets);
        next.add(new Budget(tool, calls, window));
        return new McpOptions(allowedOrigins, auth, name, agent, audit, auditArguments, next);
    }
}
