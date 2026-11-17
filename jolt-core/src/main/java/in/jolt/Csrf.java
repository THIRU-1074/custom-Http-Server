package in.jolt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Opt-in CSRF protection for POST, PUT, PATCH and DELETE. A request that carries cookies must show
 * same-site intent: {@code Sec-Fetch-Site} of same-origin, same-site or none, else an {@code
 * Origin} (or {@code Referer}) matching the {@code Host}. Failures are 403.
 */
public final class Csrf implements Middleware {

    private static final String ATTRIBUTE = "jolt.csrf";
    private static final String COOKIE = "csrf";
    private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SAME_SITE = Set.of("same-origin", "same-site", "none");
    private static final SecureRandom RANDOM = new SecureRandom();

    private boolean token;
    private boolean strict;

    private Csrf() {}

    public static Csrf protect() {
        return new Csrf();
    }

    /**
     * Also requires double-submit: the {@code csrf} cookie, which safe requests create, must equal
     * the {@code X-CSRF-Token} header or the {@code _csrf} form field.
     */
    public Csrf token() {
        token = true;
        return this;
    }

    /** Checks requests without cookies too, so API clients must send {@code Origin} as well. */
    public Csrf strict() {
        strict = true;
        return this;
    }

    /** The token for rendering forms, or {@code null} unless {@link #token()} mode is active. */
    public static String token(Request req) {
        return (String) req.attr(ATTRIBUTE);
    }

    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        if (!UNSAFE.contains(req.method())) {
            if (token) {
                issue(req, res);
            }
            next.run();
            return;
        }
        String cookies = req.header("Cookie");
        if (strict || cookies != null && !cookies.isBlank()) {
            if (!sameSite(req) || token && !tokenMatches(req)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "CSRF check failed");
            }
        }
        next.run();
    }

    private static void issue(Request req, Response res) {
        String value = req.cookie(COOKIE);
        if (value == null || value.isEmpty()) {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            res.cookie(
                    COOKIE,
                    value,
                    "Path=/; SameSite=Lax" + (res.secureCookies() ? "; Secure" : ""));
        }
        req.attr(ATTRIBUTE, value);
    }

    private static boolean sameSite(Request req) {
        String site = req.header("Sec-Fetch-Site");
        if (site != null) {
            return SAME_SITE.contains(site.toLowerCase(Locale.ROOT));
        }
        String source = req.header("Origin");
        if (source == null) {
            source = req.header("Referer");
        }
        String host = req.host();
        if (source == null || host == null) {
            return false;
        }
        try {
            String authority = URI.create(source).getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean tokenMatches(Request req) {
        String cookie = req.cookie(COOKIE);
        String sent = req.header("X-CSRF-Token");
        if (sent == null) {
            sent = req.form().get("_csrf");
        }
        return cookie != null
                && !cookie.isEmpty()
                && sent != null
                && MessageDigest.isEqual(
                        cookie.getBytes(StandardCharsets.UTF_8),
                        sent.getBytes(StandardCharsets.UTF_8));
    }
}
