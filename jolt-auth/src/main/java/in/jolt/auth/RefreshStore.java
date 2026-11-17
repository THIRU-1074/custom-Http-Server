package in.jolt.auth;

import java.time.Instant;
import java.util.Set;

/**
 * Where {@link RefreshTokens} keeps its records, keyed by the SHA-256 hash of the token. Implement
 * it to persist across restarts or processes; {@link #markUsed} must be atomic.
 */
public interface RefreshStore {

    /** One refresh token: its hash, owner, rotation family, expiry and whether it was redeemed. */
    record Entry(String hash, String subject, String family, Instant expires, boolean used) {}

    void save(Entry entry);

    /** The entry for {@code hash}, or {@code null}. */
    Entry find(String hash);

    /** Marks the entry used and returns true, or false if it was already used or is gone. */
    boolean markUsed(String hash);

    void revokeFamily(String family);

    /** Removes every entry of {@code subject} and returns the families that held them. */
    Set<String> revokeSubject(String subject);

    /** A bounded in-memory store: expired entries go first, then the oldest. */
    static RefreshStore inMemory(int maxEntries) {
        return new MemoryRefreshStore(maxEntries);
    }
}
