package in.jolt;

/**
 * What an MCP tool does, set with {@link Router#tool(String, ToolHint...)}. {@code DRY_RUN} means
 * the handler honors {@link Request#dryRun()}; {@code APPROVAL} means a human must approve each
 * call.
 */
public enum ToolHint {
    READ_ONLY,
    DESTRUCTIVE,
    IDEMPOTENT,
    OPEN_WORLD,
    DRY_RUN,
    APPROVAL
}
