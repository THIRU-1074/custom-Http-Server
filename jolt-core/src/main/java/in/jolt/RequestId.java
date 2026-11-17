package in.jolt;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in request id middleware. */
public final class RequestId {

    static final String ATTRIBUTE = "jolt.requestId";
    static final String TRACE_ATTRIBUTE = "jolt.traceId";
    private static final String HEADER = "X-Request-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TRACEPARENT =
            Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}(-.*)?");

    private RequestId() {}

    /**
     * Sets {@link Request#id()} and echoes it as {@code X-Request-Id}. An incoming id is kept only
     * if it matches {@code [A-Za-z0-9._-]{1,64}}; otherwise a random 16-hex id is generated. A
     * valid W3C {@code traceparent} sets {@link Request#traceId()}.
     */
    public static Middleware middleware() {
        return (req, res, next) -> {
            String id = req.header(HEADER);
            if (id == null || !VALID.matcher(id).matches()) {
                id = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
            }
            req.attr(ATTRIBUTE, id);
            String trace = traceId(req.header("traceparent"));
            if (trace != null) {
                req.attr(TRACE_ATTRIBUTE, trace);
            }
            res.header(HEADER, id);
            next.run();
        };
    }

    static String traceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher m = TRACEPARENT.matcher(traceparent.trim());
        if (!m.matches()) {
            return null;
        }
        String version = m.group(1);
        if (version.equals("ff") || (version.equals("00") && m.group(4) != null)) {
            return null;
        }
        if (m.group(2).chars().allMatch(c -> c == '0')
                || m.group(3).chars().allMatch(c -> c == '0')) {
            return null;
        }
        return m.group(2);
    }
}
