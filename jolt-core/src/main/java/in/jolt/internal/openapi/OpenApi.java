package in.jolt.internal.openapi;

import in.jolt.Route;
import in.jolt.internal.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a minimal OpenAPI 3.1 document from registered routes. */
public final class OpenApi {

    private OpenApi() {}

    public static String generate(List<Route> routes, String title) {
        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        for (Route r : routes) {
            List<Object> params = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (String seg : r.path().split("/")) {
                if (seg.isEmpty()) {
                    continue;
                }
                String name =
                        seg.startsWith(":") ? seg.substring(1) : seg.equals("*") ? "path" : null;
                if (name == null) {
                    sb.append('/').append(seg);
                } else {
                    sb.append("/{").append(name).append('}');
                    params.add(
                            Map.of(
                                    "name",
                                    name,
                                    "in",
                                    "path",
                                    "required",
                                    true,
                                    "schema",
                                    Map.of("type", "string")));
                }
            }
            Map<String, Object> op = new LinkedHashMap<>();
            if (r.tool() != null) {
                op.put("summary", r.tool());
            }
            if (!params.isEmpty()) {
                op.put("parameters", params);
            }
            op.put("responses", Map.of("200", Map.of("description", "OK")));
            paths.computeIfAbsent(sb.isEmpty() ? "/" : sb.toString(), k -> new LinkedHashMap<>())
                    .put(r.method().toLowerCase(Locale.ROOT), op);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.1.0");
        doc.put("info", Map.of("title", title, "version", "1.0.0"));
        doc.put("paths", paths);
        return Json.write(doc);
    }
}
