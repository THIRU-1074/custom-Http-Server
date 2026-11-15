package in.jolt.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import in.jolt.Middleware;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HS256 JWT signing and a bearer-token middleware. The secret has no default and must be supplied
 * by the caller.
 */
public final class JwtAuth {

    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    private JwtAuth(String secret) {
        Objects.requireNonNull(secret, "JWT secret must be supplied");
        if (secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be blank");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).build();
    }

    public static JwtAuth hs256(String secret) {
        return new JwtAuth(secret);
    }

    /**
     * Signs a token for {@code subject} that expires after {@code ttl}. Claim values may be String,
     * Boolean, Integer, Long, Double, Instant, Date or a Collection of those; null or any other
     * type throws IllegalArgumentException.
     */
    public String sign(String subject, Map<String, ?> claims, Duration ttl) {
        Instant now = Instant.now();
        var builder =
                JWT.create()
                        .withSubject(subject)
                        .withIssuedAt(Date.from(now))
                        .withExpiresAt(Date.from(now.plus(ttl)));
        claims.forEach(
                (k, v) -> {
                    switch (v) {
                        case null ->
                                throw new IllegalArgumentException("Claim '" + k + "' is null");
                        case String s -> builder.withClaim(k, s);
                        case Boolean b -> builder.withClaim(k, b);
                        case Integer i -> builder.withClaim(k, i);
                        case Long l -> builder.withClaim(k, l);
                        case Double d -> builder.withClaim(k, d);
                        case Instant t -> builder.withClaim(k, t);
                        case Date d -> builder.withClaim(k, d);
                        case Collection<?> c -> builder.withClaim(k, List.copyOf(c));
                        default ->
                                throw new IllegalArgumentException(
                                        "Claim '"
                                                + k
                                                + "' has unsupported type "
                                                + v.getClass().getName());
                    }
                });
        return builder.sign(algorithm);
    }

    /**
     * Requires {@code Authorization: Bearer <token>}. On success sets request attributes {@code
     * user} (the subject) and {@code claims} (a {@code Map<String, Object>}); otherwise responds
     * 401.
     */
    public Middleware middleware() {
        return (req, res, next) -> {
            String header = req.header("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                res.header("WWW-Authenticate", "Bearer").status(401).text("Missing bearer token");
                return;
            }
            DecodedJWT jwt;
            try {
                jwt = verifier.verify(header.substring(7).trim());
            } catch (JWTVerificationException e) {
                res.header("WWW-Authenticate", "Bearer")
                        .status(401)
                        .text("Invalid or expired token");
                return;
            }
            req.attr("user", jwt.getSubject()).attr("claims", claims(jwt));
            next.run();
        };
    }

    private static Map<String, Object> claims(DecodedJWT jwt) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Claim> e : jwt.getClaims().entrySet()) {
            Claim c = e.getValue();
            Object v = c.asString();
            if (v == null) {
                v = c.asLong();
            }
            if (v == null) {
                v = c.asBoolean();
            }
            if (v == null) {
                v = c.asList(Object.class);
            }
            if (v != null) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
