package in.jolt.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.HttpException;
import in.jolt.JoltConfig;
import in.jolt.Request;
import in.jolt.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

    private static final String SECRET = "test-secret-test-secret-test-secret";

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final JwtAuth jwt = JwtAuth.hs256(SECRET);

    private RefreshTokens tokens(RefreshStore store) {
        return RefreshTokens.of(jwt).store(store).clock(clock);
    }

    private static int status(Runnable r) {
        return assertThrows(HttpException.class, r::run).status();
    }

    private String subjectOf(String access) throws Exception {
        Request req =
                new Request(
                        "GET",
                        "/",
                        "",
                        Map.of("Authorization", "Bearer " + access),
                        null,
                        JoltConfig.defaults().jsonMapper());
        jwt.middleware().handle(req, new Response(JoltConfig.defaults().jsonMapper()), () -> {});
        return (String) req.attr("user");
    }

    @Test
    void issuesAnAccessTokenAndAnOpaqueRefreshToken() throws Exception {
        var pair = tokens(new MemoryRefreshStore(100)).issue("ada");
        assertEquals("ada", subjectOf(pair.accessToken()));
        assertEquals(43, pair.refreshToken().length());
        assertTrue(pair.refreshToken().matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void onlyTheHashIsStored() throws Exception {
        MemoryRefreshStore store = new MemoryRefreshStore(100);
        String token = tokens(store).issue("ada").refreshToken();
        byte[] sha =
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(sha);
        assertNull(store.find(token));
        assertEquals("ada", store.find(hash).subject());
    }

    @Test
    void refreshRotatesTheToken() throws Exception {
        RefreshTokens rt = tokens(new MemoryRefreshStore(100));
        var first = rt.issue("ada");
        var second = rt.refresh(first.refreshToken());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertEquals("ada", subjectOf(second.accessToken()));
        var third = rt.refresh(second.refreshToken());
        assertEquals("ada", subjectOf(third.accessToken()));
    }

    @Test
    void reuseRevokesTheWholeFamily() {
        List<String> hooks = new ArrayList<>();
        RefreshTokens rt =
                tokens(new MemoryRefreshStore(100))
                        .onRevoke((subject, family) -> hooks.add(subject));
        var first = rt.issue("ada");
        var second = rt.refresh(first.refreshToken());
        assertEquals(401, status(() -> rt.refresh(first.refreshToken())));
        assertEquals(List.of("ada"), hooks);
        assertEquals(401, status(() -> rt.refresh(second.refreshToken())));
    }

    @Test
    void otherFamiliesSurviveReuseDetection() {
        RefreshTokens rt = tokens(new MemoryRefreshStore(100));
        var phone = rt.issue("ada");
        var laptop = rt.issue("ada");
        rt.refresh(phone.refreshToken());
        assertEquals(401, status(() -> rt.refresh(phone.refreshToken())));
        rt.refresh(laptop.refreshToken());
    }

    @Test
    void expiredTokensAreRejected() {
        RefreshTokens rt = tokens(new MemoryRefreshStore(100)).refreshTtl(Duration.ofDays(1));
        var pair = rt.issue("ada");
        clock.now = clock.now.plus(Duration.ofHours(23));
        var next = rt.refresh(pair.refreshToken());
        clock.now = clock.now.plus(Duration.ofHours(25));
        assertEquals(401, status(() -> rt.refresh(next.refreshToken())));
    }

    @Test
    void unknownAndMalformedTokensAreRejected() {
        RefreshTokens rt = tokens(new MemoryRefreshStore(100));
        assertEquals(401, status(() -> rt.refresh("nope")));
        assertEquals(401, status(() -> rt.refresh("")));
        assertEquals(401, status(() -> rt.refresh(null)));
    }

    @Test
    void revokeInvalidatesTheTokensFamily() {
        List<String> hooks = new ArrayList<>();
        RefreshTokens rt = tokens(new MemoryRefreshStore(100)).onRevoke((s, f) -> hooks.add(s));
        var pair = rt.issue("ada");
        var other = rt.issue("ada");
        rt.revoke(pair.refreshToken());
        assertEquals(401, status(() -> rt.refresh(pair.refreshToken())));
        rt.refresh(other.refreshToken());
        assertEquals(List.of("ada"), hooks);
        rt.revoke("unknown");
        assertEquals(List.of("ada"), hooks);
    }

    @Test
    void revokeAllInvalidatesEverySessionOfTheSubject() {
        List<String> hooks = new ArrayList<>();
        RefreshTokens rt = tokens(new MemoryRefreshStore(100)).onRevoke((s, f) -> hooks.add(s));
        var a = rt.issue("ada");
        var b = rt.issue("ada");
        var other = rt.issue("bob");
        rt.revokeAll("ada");
        assertEquals(401, status(() -> rt.refresh(a.refreshToken())));
        assertEquals(401, status(() -> rt.refresh(b.refreshToken())));
        rt.refresh(other.refreshToken());
        assertEquals(List.of("ada", "ada"), hooks);
    }

    @Test
    void refreshReloadsClaims() throws Exception {
        List<String> calls = new ArrayList<>();
        RefreshTokens rt =
                tokens(new MemoryRefreshStore(100))
                        .claims(
                                subject -> {
                                    calls.add(subject);
                                    return Map.of("role", "admin");
                                });
        rt.refresh(rt.issue("ada").refreshToken());
        assertEquals(List.of("ada", "ada"), calls);
    }

    @Test
    void fullStoreOfLiveEntriesRejectsNewIssuesWith503() {
        MemoryRefreshStore store = new MemoryRefreshStore(3);
        RefreshTokens rt = tokens(store);
        var first = rt.issue("a");
        rt.issue("b");
        rt.issue("c");
        assertEquals(503, status(() -> rt.issue("d")));
        assertEquals(3, store.size());
        assertEquals(503, status(() -> rt.refresh(first.refreshToken())));
        rt.revoke(first.refreshToken());
        rt.issue("d");
    }

    @Test
    void usedTombstonesAreEvictedBeforeRejecting() {
        MemoryRefreshStore store = new MemoryRefreshStore(2);
        RefreshTokens rt = tokens(store);
        var a = rt.issue("a");
        rt.refresh(a.refreshToken());
        assertEquals(2, store.size());
        rt.issue("b");
        assertEquals(2, store.size());
    }

    @Test
    void oneSubjectCannotEvictAnothersTokens() {
        MemoryRefreshStore store = new MemoryRefreshStore(100, 3, clock);
        RefreshTokens rt = tokens(store);
        var ada = rt.issue("ada");
        List<RefreshTokens.Pair> spam = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            spam.add(rt.issue("mallory"));
        }
        rt.refresh(ada.refreshToken());
        assertEquals(401, status(() -> rt.refresh(spam.get(0).refreshToken())));
        assertEquals(401, status(() -> rt.refresh(spam.get(2).refreshToken())));
        rt.refresh(spam.get(3).refreshToken());
        rt.refresh(spam.get(5).refreshToken());
    }

    @Test
    void failingClaimsLeavesTheOldTokenUsable() {
        int[] calls = {0};
        RefreshTokens rt =
                tokens(new MemoryRefreshStore(100))
                        .claims(
                                subject -> {
                                    if (calls[0]++ == 1) {
                                        throw new IllegalStateException("db down");
                                    }
                                    return Map.of();
                                });
        var pair = rt.issue("ada");
        assertThrows(IllegalStateException.class, () -> rt.refresh(pair.refreshToken()));
        rt.refresh(pair.refreshToken());
    }

    @Test
    void expiredEntriesAreEvictedBeforeLiveOnes() {
        MemoryRefreshStore store = new MemoryRefreshStore(2, clock);
        RefreshTokens rt = tokens(store).refreshTtl(Duration.ofHours(1));
        rt.issue("old1");
        rt.issue("old2");
        clock.now = clock.now.plus(Duration.ofHours(2));
        var live = rt.issue("new");
        assertEquals(1, store.size());
        rt.refresh(live.refreshToken());
    }

    @Test
    void concurrentRedemptionHasOneWinnerAndRevokesTheFamily() throws Exception {
        for (int round = 0; round < 30; round++) {
            MemoryRefreshStore store = new MemoryRefreshStore(100);
            RefreshTokens rt = tokens(store);
            String token = rt.issue("ada").refreshToken();
            var go = new java.util.concurrent.CountDownLatch(1);
            var winners = new java.util.concurrent.ConcurrentLinkedQueue<RefreshTokens.Pair>();
            var losers = new java.util.concurrent.atomic.AtomicInteger();
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < 2; i++) {
                threads.add(
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            try {
                                                go.await();
                                                winners.add(rt.refresh(token));
                                            } catch (HttpException e) {
                                                assertEquals(401, e.status());
                                                losers.incrementAndGet();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        }));
            }
            go.countDown();
            for (Thread t : threads) {
                t.join();
            }
            assertEquals(1, winners.size());
            assertEquals(1, losers.get());
            assertEquals(0, store.size());
            assertEquals(401, status(() -> rt.refresh(winners.peek().refreshToken())));
        }
    }

    @Test
    void ttlsMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RefreshTokens.of(jwt).refreshTtl(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> RefreshTokens.of(jwt).accessTtl(Duration.ofSeconds(-1)));
    }
}
