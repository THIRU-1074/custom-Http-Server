package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JoltConfig;
import in.jolt.JsonMapper;
import in.jolt.Reply;
import in.jolt.Response;
import in.jolt.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Turns a {@link Reply} into a {@link Response}; shared by typed routes and error mapping. */
public final class Replies {

    private static final Set<String> BODY_FRAMING =
            Set.of("content-type", "content-length", "transfer-encoding");

    private static final String PROBLEM = "application/problem+json; charset=utf-8";

    private Replies() {}

    /** Sets the status and, when present, the JSON body on {@code res}. */
    public static Response write(Response res, Reply<?> reply) {
        res.status(reply.status());
        return reply.body() == null ? res : res.json(reply.body());
    }

    /**
     * Starts an error response from {@code original}: every header middleware set is kept
     * (including Set-Cookie) except Content-Type, Content-Length and Transfer-Encoding.
     */
    public static Response errorBase(Response original) {
        return original.copyHeadersExcept(BODY_FRAMING);
    }

    /** Error response for a mapped {@code reply}, see {@link #errorBase}. */
    public static Response error(Response original, Reply<?> reply, JoltConfig config) {
        Response base = errorBase(original);
        return isProblem(config, reply.status())
                ? problem(base, reply.status(), reply.body(), null, config.jsonMapper())
                : write(base, reply);
    }

    /** Error response for {@code e}: validation errors as JSON, anything else as plain text. */
    public static Response error(Response original, HttpException e, JoltConfig config) {
        Map<String, String> errors = e instanceof ValidationException v ? v.errors() : null;
        String message = String.valueOf(e.getMessage());
        Response base = errorBase(original);
        if (isProblem(config, e.status())) {
            return problem(base, e.status(), message, errors, config.jsonMapper());
        }
        return errors == null
                ? base.status(e.status()).text(message)
                : write(base, new Reply<>(e.status(), Map.of("errors", errors)));
    }

    /** Plain-text (or problem) error response, see {@link #errorBase}. */
    public static Response error(Response original, int status, String message, JoltConfig config) {
        Response base = errorBase(original);
        return isProblem(config, status)
                ? problem(base, status, message, null, config.jsonMapper())
                : base.status(status).text(message);
    }

    private static boolean isProblem(JoltConfig config, int status) {
        return config.problemJson() && status >= 400;
    }

    private static Response problem(
            Response res, int status, Object body, Map<String, String> errors, JsonMapper mapper) {
        Map<String, Object> ext = new LinkedHashMap<>();
        if (body instanceof Map<?, ?> m) {
            m.forEach((k, v) -> ext.put(String.valueOf(k), v));
        } else if (body instanceof CharSequence text) {
            ext.put("detail", text.toString());
        } else if (body != null) {
            extend(ext, body, mapper);
        }
        if (errors != null) {
            ext.put("errors", errors);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", take(ext, "type", "about:blank"));
        doc.put("title", take(ext, "title", HttpStatus.reasonFor(status)));
        doc.put("status", status);
        ext.remove("status");
        Object detail = ext.remove("detail");
        if (detail != null) {
            doc.put("detail", detail);
        }
        doc.putAll(ext);
        return res.status(status).json(doc).header("Content-Type", PROBLEM);
    }

    private static Object take(Map<String, Object> ext, String key, Object fallback) {
        Object v = ext.remove(key);
        return v == null ? fallback : v;
    }

    private static void extend(Map<String, Object> ext, Object body, JsonMapper mapper) {
        try {
            mapper.read(mapper.write(body), Map.class)
                    .forEach((k, v) -> ext.put(String.valueOf(k), v));
        } catch (IllegalArgumentException e) {
            ext.put("detail", String.valueOf(body));
        }
    }
}
