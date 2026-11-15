package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.Reply;
import in.jolt.Response;
import in.jolt.ValidationException;
import java.util.Map;
import java.util.Set;

/** Turns a {@link Reply} into a {@link Response}; shared by typed routes and error mapping. */
public final class Replies {

    private static final Set<String> BODY_FRAMING =
            Set.of("content-type", "content-length", "transfer-encoding");

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

    /** Error response for {@code reply}, see {@link #errorBase}. */
    public static Response error(Response original, Reply<?> reply) {
        return write(errorBase(original), reply);
    }

    /** Error response for {@code e}: validation errors as JSON, anything else as plain text. */
    public static Response error(Response original, HttpException e) {
        if (e instanceof ValidationException v) {
            return error(original, new Reply<>(v.status(), Map.of("errors", v.errors())));
        }
        return error(original, e.status(), String.valueOf(e.getMessage()));
    }

    /** Plain-text error response, see {@link #errorBase}. */
    public static Response error(Response original, int status, String message) {
        return errorBase(original).status(status).text(message);
    }
}
