package in.jolt.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.JoltConfig;
import in.jolt.Middleware;
import in.jolt.Request;
import in.jolt.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AuthTest {

    private static final String SECRET = "test-secret-test-secret-test-secret";

    private static Request req(String authorization) {
        Map<String, String> headers =
                authorization == null ? Map.of() : Map.of("Authorization", authorization);
        return new Request("GET", "/", "", headers, null, JoltConfig.defaults().jsonMapper());
    }

    private static Response res() {
        return new Response(JoltConfig.defaults().jsonMapper());
    }

    private static boolean run(Middleware mw, Request req, Response res) throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        mw.handle(req, res, () -> called.set(true));
        return called.get();
    }

    private static String basic(String creds) {
        return "Basic "
                + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void jwtRoundTripsSubjectAndClaims() throws Exception {
        JwtAuth auth = JwtAuth.hs256(SECRET);
        String token =
                auth.sign(
                        "thiru",
                        Map.of("role", "student", "n", 5, "big", 5L, "ok", true, "d", 1.5),
                        Duration.ofMinutes(5));
        Request r = req("Bearer " + token);
        assertTrue(run(auth.middleware(), r, res()));
        assertEquals("thiru", r.attr("user"));
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = (Map<String, Object>) r.attr("claims");
        assertEquals("student", claims.get("role"));
        assertEquals(5L, claims.get("n"));
        assertEquals(true, claims.get("ok"));
    }

    @Test
    void jwtRejectsMissingMalformedExpiredAndForgedTokens() throws Exception {
        JwtAuth auth = JwtAuth.hs256(SECRET);
        String expired = auth.sign("a", Map.of(), Duration.ofSeconds(-10));
        String forged =
                JwtAuth.hs256("another-secret-another-secret-123")
                        .sign("a", Map.of(), Duration.ofMinutes(1));
        for (String header :
                new String[] {
                    null,
                    "Bearer",
                    "Basic abc",
                    "Bearer garbage",
                    "Bearer " + expired,
                    "Bearer " + forged
                }) {
            Response res = res();
            assertFalse(run(auth.middleware(), req(header), res), String.valueOf(header));
            assertEquals(401, res.statusCode());
            assertTrue(res.headers().stream().anyMatch(h -> h.getKey().equals("WWW-Authenticate")));
        }
    }

    @Test
    void jwtSignsTemporalAndCollectionClaims() throws Exception {
        JwtAuth auth = JwtAuth.hs256(SECRET);
        Instant at = Instant.ofEpochSecond(1_700_000_000L);
        String token =
                auth.sign(
                        "u",
                        Map.of(
                                "at", at,
                                "date", Date.from(at),
                                "roles", List.of("a", "b"),
                                "ids", Set.of(7L)),
                        Duration.ofMinutes(5));
        Request r = req("Bearer " + token);
        assertTrue(run(auth.middleware(), r, res()));
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = (Map<String, Object>) r.attr("claims");
        assertEquals(1_700_000_000L, claims.get("at"));
        assertEquals(1_700_000_000L, claims.get("date"));
        assertEquals(List.of("a", "b"), claims.get("roles"));
        assertEquals(List.of(7), claims.get("ids"));
    }

    @Test
    void jwtRejectsNullAndUnsupportedClaimValues() {
        JwtAuth auth = JwtAuth.hs256(SECRET);
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("role", null);
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> auth.sign("u", withNull, Duration.ofMinutes(1)));
        assertTrue(e.getMessage().contains("role"), e.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> auth.sign("u", Map.of("o", new Object()), Duration.ofMinutes(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> auth.sign("u", Map.of("m", Map.of("k", "v")), Duration.ofMinutes(1)));
    }

    @Test
    void jwtRequiresACallerSuppliedSecret() {
        assertThrows(NullPointerException.class, () -> JwtAuth.hs256(null));
        assertThrows(IllegalArgumentException.class, () -> JwtAuth.hs256("  "));
        assertThrows(IllegalArgumentException.class, () -> JwtAuth.hs256("a".repeat(31)));
        JwtAuth.hs256("a".repeat(32));
    }

    @Test
    void jwtPinsHs256AndRequiresExpiry() throws Exception {
        JwtAuth auth = JwtAuth.hs256(SECRET);
        String noExp =
                com.auth0
                        .jwt
                        .JWT
                        .create()
                        .withSubject("x")
                        .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(SECRET));
        String hs512 =
                com.auth0
                        .jwt
                        .JWT
                        .create()
                        .withSubject("x")
                        .withExpiresAt(java.util.Date.from(java.time.Instant.now().plusSeconds(60)))
                        .sign(com.auth0.jwt.algorithms.Algorithm.HMAC512(SECRET));
        String none =
                com.auth0
                        .jwt
                        .JWT
                        .create()
                        .withSubject("x")
                        .withExpiresAt(java.util.Date.from(java.time.Instant.now().plusSeconds(60)))
                        .sign(com.auth0.jwt.algorithms.Algorithm.none());
        for (String token : new String[] {noExp, hs512, none}) {
            Response res = res();
            assertFalse(run(auth.middleware(), req("Bearer " + token), res));
            assertEquals(401, res.statusCode());
        }
    }

    @Test
    void basicAuthPassesValidCredentialsIncludingColonsInThePassword() throws Exception {
        Middleware mw = BasicAuth.require((u, p) -> u.equals("bob") && p.equals("pa:ss"));
        Request r = req(basic("bob:pa:ss"));
        assertTrue(run(mw, r, res()));
        assertEquals("bob", r.attr("user"));
    }

    @Test
    void basicAuthRejectsBadMissingAndMalformedCredentials() throws Exception {
        Middleware mw = BasicAuth.require((u, p) -> false);
        for (String header :
                new String[] {
                    null, "Bearer x", basic("bob:x"), basic("nocolon"), "Basic !!!not-base64"
                }) {
            Response res = res();
            Request r = req(header);
            assertFalse(run(mw, r, res), String.valueOf(header));
            assertEquals(401, res.statusCode());
            assertNull(r.attr("user"));
        }
    }
}
