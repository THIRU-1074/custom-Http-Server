package in.jolt.mcp;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JsonMapper;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.Route;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exposes routes tagged with {@code .tool("description")} as MCP tools over Streamable HTTP
 * (JSON-RPC 2.0 via POST). Tool calls run through the app's normal middleware and routing.
 */
public final class Mcp {

    private static final String PROTOCOL = "2025-06-18";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final App app;
    private final McpOptions options;
    private final JsonMapper mapper;

    private Mcp(App app, McpOptions options) {
        this.app = app;
        this.options = options;
        this.mapper = app.config().jsonMapper();
    }

    /** Registers the MCP endpoint at {@code path}. Nothing is exposed until you call this. */
    public static void expose(App app, String path, McpOptions options) {
        Mcp mcp = new Mcp(app, options);
        app.use(
                path,
                (req, res, next) -> {
                    String origin = req.header("Origin");
                    if (origin != null && !options.allowedOrigins().contains(origin)) {
                        throw new HttpException(
                                HttpStatus.FORBIDDEN,
                                "Origin "
                                        + origin
                                        + " not allowed; add it to McpOptions.allowOrigins");
                    }
                    next.run();
                });
        if (options.auth() != null) {
            app.use(path, options.auth());
        }
        app.post(path, mcp::handle);
    }

    private void handle(Request req, Response res) {
        Map<?, ?> msg;
        try {
            msg = mapper.read(req.body(), Map.class);
        } catch (IllegalArgumentException e) {
            res.json(error(null, -32700, "Parse error: " + e.getMessage()));
            return;
        }
        Object id = msg.get("id");
        String method = String.valueOf(msg.get("method"));
        if (!msg.containsKey("id")) {
            res.status(HttpStatus.ACCEPTED).send(new byte[0]);
            return;
        }
        Map<?, ?> params = msg.get("params") instanceof Map<?, ?> p ? p : Map.of();
        switch (method) {
            case "initialize" ->
                    res.json(
                            result(
                                    id,
                                    Map.of(
                                            "protocolVersion", PROTOCOL,
                                            "capabilities", Map.of("tools", Map.of()),
                                            "serverInfo",
                                                    Map.of(
                                                            "name",
                                                            options.name(),
                                                            "version",
                                                            "1"))));
            case "ping" -> res.json(result(id, Map.of()));
            case "tools/list" -> res.json(result(id, Map.of("tools", listTools())));
            case "tools/call" -> res.json(callTool(req, id, params));
            default -> res.json(error(id, -32601, "Method not found: " + method));
        }
    }

    private Map<String, String> toolNames(List<Route> tools) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Route r : tools) {
            StringBuilder n = new StringBuilder(r.method().toLowerCase());
            for (String seg : r.path().split("/")) {
                if (!seg.isEmpty()) {
                    n.append('_').append(seg.replaceAll("[^A-Za-z0-9]", ""));
                }
            }
            String name = n.toString();
            for (int i = 2; !used.add(name); i++) {
                name = n + "_" + i;
            }
            names.put(name, r.method() + " " + r.path());
        }
        return names;
    }

    private List<Route> tools() {
        return app.routes().stream().filter(r -> r.tool() != null).toList();
    }

    private List<Object> listTools() {
        List<Route> tools = tools();
        List<Object> out = new ArrayList<>();
        int i = 0;
        for (String name : toolNames(tools).keySet()) {
            Route r = tools.get(i++);
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (String p : pathParams(r.path())) {
                props.put(p, Map.of("type", "string"));
                required.add(p);
            }
            props.put("query", Map.of("type", "object", "description", "Query parameters"));
            if (BODY_METHODS.contains(r.method())) {
                props.put("body", Map.of("type", "object", "description", "JSON request body"));
            }
            out.add(
                    Map.of(
                            "name", name,
                            "description", r.tool(),
                            "inputSchema",
                                    Map.of(
                                            "type", "object",
                                            "properties", props,
                                            "required", required)));
        }
        return out;
    }

    private Map<String, Object> callTool(Request outer, Object id, Map<?, ?> params) {
        List<Route> tools = tools();
        Map<String, String> names = toolNames(tools);
        String target = names.get(String.valueOf(params.get("name")));
        if (target == null) {
            return error(id, -32602, "Unknown tool: " + params.get("name"));
        }
        Route route = null;
        for (Route r : tools) {
            if ((r.method() + " " + r.path()).equals(target)) {
                route = r;
            }
        }
        Map<?, ?> args = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
        StringBuilder path = new StringBuilder();
        for (String seg : route.path().split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.startsWith(":")) {
                Object v = args.get(seg.substring(1));
                if (v == null || String.valueOf(v).contains("/")) {
                    return error(id, -32602, "Missing or invalid argument: " + seg.substring(1));
                }
                seg = String.valueOf(v);
            }
            path.append('/').append(seg);
        }
        StringBuilder query = new StringBuilder();
        if (args.get("query") instanceof Map<?, ?> q) {
            q.forEach(
                    (k, v) -> {
                        if (!query.isEmpty()) {
                            query.append('&');
                        }
                        query.append(URLEncoder.encode(String.valueOf(k), StandardCharsets.UTF_8))
                                .append('=')
                                .append(
                                        URLEncoder.encode(
                                                String.valueOf(v), StandardCharsets.UTF_8));
                    });
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : List.of("Authorization", "Cookie")) {
            if (outer.header(h) != null) {
                headers.put(h, outer.header(h));
            }
        }
        byte[] body = null;
        if (args.get("body") != null) {
            body = mapper.write(args.get("body"));
            headers.put("Content-Type", "application/json");
        }
        Request inner =
                new Request(
                        route.method(),
                        path.length() == 0 ? "/" : path.toString(),
                        query.toString(),
                        headers,
                        body,
                        mapper);
        Response out = new Response(mapper);
        try {
            app.dispatch(inner, out);
        } catch (HttpException e) {
            out = new Response(mapper).status(e.status()).text(String.valueOf(e.getMessage()));
        } catch (Exception e) {
            out = new Response(mapper).status(500).text("Internal Server Error");
        }
        boolean failed = out.statusCode() >= 400;
        String text = new String(out.body(), StandardCharsets.UTF_8);
        if (failed) {
            text = "HTTP " + out.statusCode() + ": " + text;
        }
        return result(
                id,
                Map.of(
                        "content",
                        List.of(Map.of("type", "text", "text", text)),
                        "isError",
                        failed));
    }

    private static List<String> pathParams(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.startsWith(":")) {
                out.add(seg.substring(1));
            }
        }
        return out;
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }
}
