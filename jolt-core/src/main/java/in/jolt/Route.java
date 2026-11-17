package in.jolt;

import in.jolt.internal.json.Schema;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A registered route. {@code tool} is the MCP tool description, or {@code null}; {@code input} is
 * the JSON body type of a typed route, or {@code null}; {@code hints} are the tool's {@link
 * ToolHint}s, empty for a plain route.
 */
public record Route(String method, String path, String tool, Class<?> input, Set<ToolHint> hints) {

    public Route {
        hints = hints == null || hints.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(hints));
    }

    public Route(String method, String path, String tool) {
        this(method, path, tool, null, Set.of());
    }

    public Route(String method, String path, String tool, Class<?> input) {
        this(method, path, tool, input, Set.of());
    }

    /** JSON Schema of {@code input}, or {@code null} for an untyped route. */
    public Map<String, Object> inputSchema() {
        return input == null ? null : Schema.of(input);
    }

    /** Whether {@code hint} is set on this route. */
    public boolean has(ToolHint hint) {
        return hints.contains(hint);
    }

    Route under(String prefix) {
        return new Route(method, prefix + path, tool, input, hints);
    }
}
