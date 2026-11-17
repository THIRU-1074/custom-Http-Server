package in.jolt.mcp;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JsonMapper;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.Route;
import in.jolt.internal.limit.FixedWindow;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Exposes routes tagged with {@code .tool("description")} as MCP tools over Streamable HTTP
 * (JSON-RPC 2.0 via POST). Tool calls run through the app's normal middleware and routing.
 */
public final class Mcp {

    private static final String PROTOCOL = "2025-06-18";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final List<String> FORWARDED =
            List.of("Authorization", "Cookie", "X-Request-Id", "traceparent", "tracestate");
    private static final int MAX_AGENTS = 10_000;
    private static final int MAX_CLIENT_LABEL = 100;
    private static final System.Logger LOG = System.getLogger("in.jolt.mcp");

    private final App app;
    private final McpOptions options;
    private final JsonMapper mapper;
    private final Consumer<ToolCall> audit;
    private final List<FixedWindow> budgets = new ArrayList<>();

    private Mcp(App app, McpOptions options, LongSupplier nanos) {
        this.app = app;
        this.options = options;
        this.mapper = app.config().jsonMapper();
        this.audit = options.audit() != null ? options.audit() : this::log;
        for (McpOptions.Budget b : options.budgets()) {
            long windowNanos;
            try {
                windowNanos = b.window().toNanos();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("budget window is too large", e);
            }
            budgets.add(new FixedWindow(b.calls(), windowNanos, MAX_AGENTS, nanos));
        }
    }

    /** Registers the MCP endpoint at {@code path}. Nothing is exposed until you call this. */
    public static void expose(App app, String path, McpOptions options) {
        expose(app, path, options, System::nanoTime);
    }

    static void expose(App app, String path, McpOptions options, LongSupplier nanos) {
        Mcp mcp = new Mcp(app, options, nanos);
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
        String toolName = String.valueOf(params.get("name"));
        String target = names.get(toolName);
        if (target == null) {
            return error(id, -32602, "Unknown tool: " + params.get("name"));
        }
        long start = System.nanoTime();
        String agent = agent(outer);
        Map<?, ?> args = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
        int[] status = {500};
        try {
            return runTool(outer, id, tools, toolName, target, agent, args, status);
        } finally {
            String client = outer.header("User-Agent");
            if (client != null && client.length() > MAX_CLIENT_LABEL) {
                client = client.substring(0, MAX_CLIENT_LABEL);
            }
            int space = target.indexOf(' ');
            audit.accept(
                    new ToolCall(
                            Instant.now(),
                            agent,
                            client,
                            toolName,
                            target.substring(0, space),
                            target.substring(space + 1),
                            status[0],
                            Duration.ofNanos(System.nanoTime() - start),
                            outer.id(),
                            options.auditArguments()
                                    ? Collections.unmodifiableMap(new LinkedHashMap<>(args))
                                    : null));
        }
    }

    private Map<String, Object> runTool(
            Request outer,
            Object id,
            List<Route> tools,
            String toolName,
            String target,
            String agent,
            Map<?, ?> args,
            int[] status) {
        for (int i = 0; i < budgets.size(); i++) {
            McpOptions.Budget b = options.budgets().get(i);
            if (!b.tool().equals("*") && !b.tool().equals(toolName)) {
                continue;
            }
            long retry = budgets.get(i).acquire(agent);
            if (retry >= 0) {
                status[0] = 429;
                return toolError(
                        id,
                        "Budget exceeded for agent "
                                + agent
                                + " on "
                                + toolName
                                + "; retry in "
                                + FixedWindow.seconds(retry)
                                + "s");
            }
        }
        Route route = null;
        for (Route r : tools) {
            if ((r.method() + " " + r.path()).equals(target)) {
                route = r;
            }
        }
        StringBuilder path = new StringBuilder();
        for (String seg : route.path().split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.startsWith(":")) {
                Object v = args.get(seg.substring(1));
                if (v == null || String.valueOf(v).contains("/")) {
                    status[0] = 400;
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
        for (String h : FORWARDED) {
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
        status[0] = out.statusCode();
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

    private String agent(Request req) {
        String a = options.agent() == null ? null : options.agent().apply(req);
        return a == null || a.isBlank() ? "anonymous" : a;
    }

    private void log(ToolCall c) {
        StringBuilder line =
                new StringBuilder("tool=")
                        .append(c.tool())
                        .append(" agent=")
                        .append(c.agent())
                        .append(" status=")
                        .append(c.status())
                        .append(' ')
                        .append(c.took().toMillis())
                        .append("ms");
        if (c.requestId() != null) {
            line.append(" id=").append(c.requestId());
        }
        if (c.arguments() != null) {
            line.append(" args=")
                    .append(new String(mapper.write(c.arguments()), StandardCharsets.UTF_8));
        }
        LOG.log(System.Logger.Level.INFO, printable(line.toString()));
    }

    private static String printable(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
        return sb.toString();
    }

    private static Map<String, Object> toolError(Object id, String text) {
        return result(
                id,
                Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", true));
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
