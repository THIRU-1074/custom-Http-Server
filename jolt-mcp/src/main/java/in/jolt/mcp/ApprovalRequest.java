package in.jolt.mcp;

import java.time.Instant;
import java.util.Map;

/**
 * A tool call waiting for a human, as sent to {@link McpOptions#approvals}. Show {@code arguments}
 * to the approver; they are exactly what will run.
 */
public record ApprovalRequest(
        String id,
        String agent,
        String tool,
        String method,
        String path,
        Map<?, ?> arguments,
        Instant expiresAt) {}
