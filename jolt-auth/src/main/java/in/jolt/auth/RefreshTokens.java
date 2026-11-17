package in.jolt.auth;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Short-lived JWT access tokens paired with rotating opaque refresh tokens. Each refresh token is
 * single use; redeeming one a second time revokes its whole family, so a stolen token is detected.
 * Only a SHA-256 hash of a refresh token is stored.
 */
public final class RefreshTokens {

    public record Pair(String accessToken, String refreshToken) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final JwtAuth jwt;
    private RefreshStore store = RefreshStore.inMemory(10_000);
    private Duration accessTtl = Duration.ofMinutes(15);
    private Duration refreshTtl = Duration.ofDays(30);
    private Function<String, Map<String, ?>> claims = subject -> Map.of();
    private BiConsumer<String, String> onRevoke = (subject, family) -> {};
    private Clock clock = Clock.systemUTC();

    private RefreshTokens(JwtAuth jwt) {
        this.jwt = Objects.requireNonNull(jwt);
    }

    /**
     * Access tokens last 15 minutes, refresh tokens 30 days, kept in a 10000-entry memory store.
     */
    public static RefreshTokens of(JwtAuth jwt) {
        return new RefreshTokens(jwt);
    }

    public RefreshTokens accessTtl(Duration ttl) {
        this.accessTtl = positive(ttl);
        return this;
    }

    public RefreshTokens refreshTtl(Duration ttl) {
        this.refreshTtl = positive(ttl);
        return this;
    }

    public RefreshTokens store(RefreshStore store) {
        this.store = Objects.requireNonNull(store);
        return this;
    }

    /** Claims for a subject's access tokens, read again on every refresh. */
    public RefreshTokens claims(Function<String, Map<String, ?>> claims) {
        this.claims = Objects.requireNonNull(claims);
        return this;
    }

    /** Called with the subject and family id each time a family is revoked. */
    public RefreshTokens onRevoke(BiConsumer<String, String> hook) {
        this.onRevoke = Objects.requireNonNull(hook);
        return this;
    }

    RefreshTokens clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /** Starts a new session for {@code subject}. */
    public Pair issue(String subject) {
        return pair(subject, random(16));
    }

    /**
     * Redeems {@code token} for a new pair and invalidates it.
     *
     * @throws HttpException 401 if the token is unknown, expired or revoked; a token that was
     *     already redeemed also revokes its family
     */
    public Pair refresh(String token) {
        RefreshStore.Entry entry =
                token == null || token.isEmpty() ? null : store.find(hash(token));
        if (entry == null || !entry.expires().isAfter(clock.instant())) {
            throw invalid();
        }
        if (!store.markUsed(entry.hash())) {
            revokeFamily(entry.subject(), entry.family());
            throw invalid();
        }
        return pair(entry.subject(), entry.family());
    }

    /** Revokes the session this token belongs to. Unknown tokens are ignored. */
    public void revoke(String token) {
        RefreshStore.Entry entry = token == null ? null : store.find(hash(token));
        if (entry != null) {
            revokeFamily(entry.subject(), entry.family());
        }
    }

    public void revokeAll(String subject) {
        for (String family : store.revokeSubject(subject)) {
            onRevoke.accept(subject, family);
        }
    }

    private void revokeFamily(String subject, String family) {
        store.revokeFamily(family);
        onRevoke.accept(subject, family);
    }

    private Pair pair(String subject, String family) {
        String refresh = random(32);
        store.save(
                new RefreshStore.Entry(
                        hash(refresh), subject, family, clock.instant().plus(refreshTtl), false));
        return new Pair(jwt.sign(subject, claims.apply(subject), accessTtl), refresh);
    }

    private static HttpException invalid() {
        return new HttpException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
    }

    private static Duration positive(Duration d) {
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException("TTL must be positive");
        }
        return d;
    }

    private static String random(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return B64.encodeToString(raw);
    }

    private static String hash(String token) {
        try {
            return B64.encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
