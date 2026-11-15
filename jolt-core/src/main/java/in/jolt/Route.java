package in.jolt;

/** A registered route. {@code tool} is the MCP tool description, or {@code null}. */
public record Route(String method, String path, String tool) {}
