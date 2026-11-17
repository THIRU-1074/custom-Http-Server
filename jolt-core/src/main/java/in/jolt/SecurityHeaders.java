package in.jolt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Opt-in middleware that adds browser hardening headers to every reply, including error replies.
 * Instances are immutable; each option returns a copy.
 */
public final class SecurityHeaders implements Middleware {

    private static final List<Map.Entry<String, String>> BASE =
            List.of(
                    Map.entry("X-Content-Type-Options", "nosniff"),
                    Map.entry("X-Frame-Options", "DENY"),
                    Map.entry("Referrer-Policy", "no-referrer"),
                    Map.entry("Cross-Origin-Opener-Policy", "same-origin"),
                    Map.entry("Cross-Origin-Resource-Policy", "same-origin"));

    private final List<Map.Entry<String, String>> always;
    private final String hsts;
    private final Predicate<Request> https;

    private SecurityHeaders(
            List<Map.Entry<String, String>> always, String hsts, Predicate<Request> https) {
        this.always = always;
        this.hsts = hsts;
        this.https = https;
    }

    /** The five default headers; HSTS and CSP are off. */
    public static SecurityHeaders defaults() {
        return new SecurityHeaders(BASE, null, null);
    }

    /**
     * Adds {@code Strict-Transport-Security} with {@code includeSubDomains}, sent only for requests
     * {@code isHttps} accepts (for example, a trusted-proxy check), so it never goes out over plain
     * HTTP.
     */
    public SecurityHeaders hsts(Duration maxAge, Predicate<Request> isHttps) {
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("HSTS max-age must be positive");
        }
        return new SecurityHeaders(
                always,
                "max-age=" + maxAge.toSeconds() + "; includeSubDomains",
                java.util.Objects.requireNonNull(isHttps, "isHttps"));
    }

    /** Adds a {@code Content-Security-Policy} header with the given policy. */
    public SecurityHeaders csp(String policy) {
        List<Map.Entry<String, String>> next = new ArrayList<>(always);
        next.add(Map.entry("Content-Security-Policy", policy));
        Response.checkHeader("Content-Security-Policy", policy);
        return new SecurityHeaders(List.copyOf(next), hsts, https);
    }

    String hstsValue() {
        return hsts;
    }

    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        for (var h : always) {
            res.header(h.getKey(), h.getValue());
        }
        if (hsts != null && https.test(req)) {
            res.header("Strict-Transport-Security", hsts);
        }
        next.run();
    }
}
