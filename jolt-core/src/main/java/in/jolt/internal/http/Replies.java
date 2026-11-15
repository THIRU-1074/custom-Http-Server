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
        return config.problemJson()
                ? problem(base, reply.status(), reply.body(), null, config.jsonMapper())
                : write(base, reply);
    }

    /** Error response for {@code e}: validation errors as JSON, anything else as plain text. */
    public static Response error(Response original, HttpException e, JoltConfig config) {
        Map<String, String> errors = e instanceof ValidationException v ? v.errors() : null;
        if (config.problemJson()) {
            return problem(
                    errorBase(original),
                    e.status(),
                    String.valueOf(e.getMessage()),
                    errors,
                    config.jsonMapper());
        }
        if (errors != null) {
            return error(original, new Reply<>(e.status(), Map.of("errors", errors)), config);
        }
        return error(original, e.status(), String.valueOf(e.getMessage()), config);
    }

    /** Plain-text (or problem) error response, see {@link #errorBase}. */
    public static Response error(Response original, int status, String message, JoltConfig config) {
        Response base = errorBase(original);
        return config.problemJson()
                ? problem(base, status, message, null, config.jsonMapper())
                : base.status(status).text(message);
    }

    private static Response problem(
            Response res, int status, Object body, Map<String, String> errors, JsonMapper mapper) {
        res.status(status);
        if (status < 200 || status == 204 || status == 304) {
            return res;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "about:blank");
        doc.put("title", HttpStatus.reasonFor(status));
        doc.put("status", status);
        if (body instanceof Map<?, ?> m) {
            m.forEach((k, v) -> doc.put(String.valueOf(k), v));
        } else if (body instanceof CharSequence text) {
            doc.put("detail", text.toString());
        } else if (body != null) {
            extend(doc, body, mapper);
        }
        if (errors != null) {
            doc.put("errors", errors);
        }
        doc.put("status", status);
        return res.json(doc).header("Content-Type", PROBLEM);
    }

    private static void extend(Map<String, Object> doc, Object body, JsonMapper mapper) {
        try {
            mapper.read(mapper.write(body), Map.class)
                    .forEach((k, v) -> doc.put(String.valueOf(k), v));
        } catch (IllegalArgumentException e) {
            doc.put("detail", String.valueOf(body));
        }
    }
}
