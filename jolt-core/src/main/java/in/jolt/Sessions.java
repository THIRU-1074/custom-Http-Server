package in.jolt;

import in.jolt.internal.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cookie sessions signed with HMAC-SHA256: the data lives in the cookie, so there is no server
 * store. The cookie is {@code HttpOnly; SameSite=Lax; Path=/}, plus {@code Secure} per {@link
 * JoltConfig#secureCookies}. It is signed, not encrypted: do not store secrets in it.
 */
public final class Sessions implements Middleware {

    private static final String ATTRIBUTE = "jolt.session";
    private static final String DELETE = "Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder UNB64 = Base64.getUrlDecoder();

    private final List<byte[]> keys = new ArrayList<>();
    private String name = "session";
    private Duration maxAge = Duration.ofHours(1);
    private Clock clock = Clock.systemUTC();

    private Sessions(byte[] secret) {
        keys.add(check(secret));
    }

    public static Sessions signed(byte[] secret) {
        return new Sessions(secret);
    }

    /** As above; the secret's UTF-8 bytes must number at least 32. */
    public static Sessions signed(String secret) {
        return new Sessions(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The session for this request.
     *
     * @throws IllegalStateException if the middleware is not installed
     */
    public static Session of(Request req) {
        Object s = req.attr(ATTRIBUTE);
        if (s == null) {
            throw new IllegalStateException("Sessions middleware is not installed");
        }
        return (Session) s;
    }

    /** Accepts cookies signed with these older secrets too, and re-signs them with the first. */
    public Sessions previous(byte[]... secrets) {
        for (byte[] k : secrets) {
            keys.add(check(k));
        }
        return this;
    }

    public Sessions previous(String... secrets) {
        for (String k : secrets) {
            keys.add(check(k.getBytes(StandardCharsets.UTF_8)));
        }
        return this;
    }

    /** Session lifetime, default one hour; it is renewed once half of it has passed. */
    public Sessions maxAge(Duration maxAge) {
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        this.maxAge = maxAge;
        return this;
    }

    /** Cookie name, default {@code session}. */
    public Sessions cookie(String name) {
        this.name = name;
        return this;
    }

    Sessions clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        long now = clock.instant().getEpochSecond();
        long[] expiry = {0};
        boolean[] stale = {false};
        Session session = load(req.cookie(name), now, expiry, stale);
        req.attr(ATTRIBUTE, session);
        next.run();
        if (session.isNew() && session.isEmpty()) {
            return;
        }
        boolean renew = !session.isNew() && expiry[0] - now < maxAge.toSeconds() / 2;
        if (!session.changed() && !renew && !stale[0]) {
            return;
        }
        if (session.isEmpty()) {
            res.cookie(name, "", DELETE);
        } else {
            res.cookie(name, encode(session, now + maxAge.toSeconds()));
        }
    }

    private Session load(String cookie, long now, long[] expiry, boolean[] stale) {
        if (cookie != null) {
            int dot = cookie.indexOf('.');
            if (dot > 0 && dot < cookie.length() - 1) {
                String payload = cookie.substring(0, dot);
                byte[] mac = decode(cookie.substring(dot + 1));
                int used = verify(payload, mac);
                if (used >= 0) {
                    Session s = parse(payload, now, expiry);
                    if (s != null) {
                        stale[0] = used > 0;
                        return s;
                    }
                }
            }
        }
        return new Session();
    }

    private int verify(String payload, byte[] mac) {
        if (mac == null) {
            return -1;
        }
        int match = -1;
        for (int i = 0; i < keys.size(); i++) {
            if (MessageDigest.isEqual(sign(keys.get(i), payload), mac) && match < 0) {
                match = i;
            }
        }
        return match;
    }

    @SuppressWarnings("unchecked")
    private static Session parse(String payload, long now, long[] expiry) {
        try {
            byte[] raw = UNB64.decode(payload);
            Map<String, Object> envelope =
                    (Map<String, Object>) Json.parse(new String(raw, StandardCharsets.UTF_8));
            long e = ((Number) envelope.get("e")).longValue();
            if (e <= now) {
                return null;
            }
            expiry[0] = e;
            Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) envelope.get("d"));
            return new Session(data, false);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String encode(Session session, long expires) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("e", expires);
        envelope.put("d", session.data());
        String payload = B64.encodeToString(Json.write(envelope).getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(sign(keys.get(0), payload));
    }

    private static byte[] sign(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] decode(String s) {
        try {
            return UNB64.decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] check(byte[] secret) {
        if (secret.length < 32) {
            throw new IllegalArgumentException("Session secret must be at least 32 bytes");
        }
        return secret.clone();
    }
}
