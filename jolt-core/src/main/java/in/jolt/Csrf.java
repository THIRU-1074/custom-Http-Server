package in.jolt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opt-in CSRF protection for POST, PUT, PATCH and DELETE: a request with cookies needs a same-site
 * {@code Sec-Fetch-Site} or an {@code Origin}/{@code Referer} matching {@code Host}, else 403. Safe
 * methods pass.
 */
public final class Csrf implements Middleware {

    private static final String ATTRIBUTE = "jolt.csrf";
    private static final String COOKIE = "csrf";
    private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private boolean token;
    private boolean strict;
    private byte[] secret;

    private Csrf() {}

    public static Csrf protect() {
        return new Csrf();
    }

    /**
     * Adds double-submit: the {@code csrf} cookie (created on safe requests) must equal the {@code
     * X-CSRF-Token} header or the {@code _csrf} field of a form or multipart body; other content
     * types need the header. Only {@code same-origin} and {@code none} Fetch Metadata pass.
     *
     * <p>The unsigned token is weaker: a sibling subdomain can set the cookie ("cookie tossing").
     * Prefer {@link #token(byte[])}.
     */
    public Csrf token() {
        token = true;
        return this;
    }

    /**
     * As {@link #token()}, with tokens signed by HMAC-SHA256 under {@code secret} so a cookie the
     * server did not issue fails. The token is not bound to a user or session; for that, keep it in
     * {@link Sessions}.
     *
     * @throws IllegalArgumentException if the secret is under 32 bytes
     */
    public Csrf token(byte[] secret) {
        if (secret.length < 32) {
            throw new IllegalArgumentException("CSRF secret must be at least 32 bytes");
        }
        this.secret = secret.clone();
        return token();
    }

    /** Checks requests without cookies too, so API clients must send {@code Origin} as well. */
    public Csrf strict() {
        strict = true;
        return this;
    }

    /** The token for rendering forms, or {@code null} unless token mode is active. */
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
        if (token) {
            String current = valid(req.cookie(COOKIE));
            if (current != null) {
                req.attr(ATTRIBUTE, current);
            }
        }
        next.run();
    }

    private void issue(Request req, Response res) {
        String value = valid(req.cookie(COOKIE));
        if (value == null) {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            value = B64.encodeToString(raw);
            if (secret != null) {
                value = value + "." + B64.encodeToString(mac(value));
            }
            res.cookie(
                    COOKIE,
                    value,
                    "Path=/; SameSite=Lax" + (res.secureCookies() ? "; Secure" : ""));
        }
        req.attr(ATTRIBUTE, value);
    }

    private String valid(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return null;
        }
        if (secret == null) {
            return cookie;
        }
        int dot = cookie.indexOf('.');
        if (dot < 1) {
            return null;
        }
        try {
            String tail = cookie.substring(dot + 1);
            byte[] given = Base64.getUrlDecoder().decode(tail);
            boolean canonical = B64.encodeToString(given).equals(tail);
            return canonical && MessageDigest.isEqual(mac(cookie.substring(0, dot)), given)
                    ? cookie
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] mac(String random) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(random.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private boolean sameSite(Request req) {
        String site = req.header("Sec-Fetch-Site");
        if (site != null) {
            String s = site.toLowerCase(Locale.ROOT);
            return s.equals("same-origin") || s.equals("none") || !token && s.equals("same-site");
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

    private boolean tokenMatches(Request req) {
        String cookie = valid(req.cookie(COOKIE));
        String sent = req.header("X-CSRF-Token");
        if (sent == null) {
            sent = field(req);
        }
        return cookie != null
                && sent != null
                && MessageDigest.isEqual(
                        cookie.getBytes(StandardCharsets.UTF_8),
                        sent.getBytes(StandardCharsets.UTF_8));
    }

    private static String field(Request req) {
        String type = req.header("Content-Type");
        if (type != null && type.regionMatches(true, 0, "multipart/form-data", 0, 19)) {
            for (Part part : req.parts()) {
                if (part.filename() == null && part.name().equals("_csrf")) {
                    return part.text();
                }
            }
            return null;
        }
        return req.form().get("_csrf");
    }
}
