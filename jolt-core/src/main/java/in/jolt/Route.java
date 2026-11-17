package in.jolt;

import in.jolt.internal.json.Schema;
import java.util.Map;

/**
 * A registered route. {@code tool} is the MCP tool description, or {@code null}; {@code input} is
 * the JSON body type of a typed route, or {@code null}.
 */
public record Route(String method, String path, String tool, Class<?> input) {

    public Route(String method, String path, String tool) {
        this(method, path, tool, null);
    }

    /** JSON Schema of {@code input}, or {@code null} for an untyped route. */
    public Map<String, Object> inputSchema() {
        return input == null ? null : Schema.of(input);
    }

    Route under(String prefix) {
        return new Route(method, prefix + path, tool, input);
    }
}
