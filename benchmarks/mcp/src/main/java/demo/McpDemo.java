package demo;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.mcp.Mcp;
import in.jolt.mcp.McpOptions;

/** Smallest runnable MCP server: one tagged route exposed at POST /mcp on port 8090. */
public class McpDemo {

    record Todo(int id, String title) {}

    public static void main(String[] args) {
        App app = Jolt.create();
        app.get(
                        "/todos/:id",
                        (req, res) -> res.json(new Todo(Integer.parseInt(req.param("id")), "demo")))
                .tool("Fetch a todo by id");
        Mcp.expose(app, "/mcp", McpOptions.defaults().allowOrigins("http://localhost:3000"));
        app.listen(8090);
    }
}
