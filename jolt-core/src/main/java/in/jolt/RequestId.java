package in.jolt;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Opt-in request id middleware. */
public final class RequestId {

    static final String ATTRIBUTE = "jolt.requestId";
    private static final String HEADER = "X-Request-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestId() {}

    /**
     * Sets {@link Request#id()} and echoes it as {@code X-Request-Id}. An incoming id is kept only
     * if it matches {@code [A-Za-z0-9._-]{1,64}}; otherwise a random 16-hex id is generated.
     */
    public static Middleware middleware() {
        return (req, res, next) -> {
            String id = req.header(HEADER);
            if (id == null || !VALID.matcher(id).matches()) {
                id = String.format("%016x", ThreadLocalRandom.current().nextLong());
            }
            req.attr(ATTRIBUTE, id);
            res.header(HEADER, id);
            next.run();
        };
    }
}
