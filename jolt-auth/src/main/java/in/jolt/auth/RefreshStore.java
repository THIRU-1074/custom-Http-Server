package in.jolt.auth;

import java.time.Instant;
import java.util.Set;

/**
 * Where {@link RefreshTokens} keeps its records, keyed by the SHA-256 hash of the token. Implement
 * it to persist across restarts or processes; {@link #rotate} must be atomic.
 */
public interface RefreshStore {

    /** One refresh token: its hash, owner, rotation family, expiry and whether it was redeemed. */
    record Entry(String hash, String subject, String family, Instant expires, boolean used) {}

    /**
     * Saves the first token of a new family.
     *
     * @throws IllegalStateException if the store is full of live entries
     */
    void save(Entry entry);

    /** The entry for {@code hash}, or {@code null}. */
    Entry find(String hash);

    /**
     * Atomically marks {@code oldHash} used and saves {@code next}. Returns false, changing
     * nothing, if the old entry is gone, already used, or its family was revoked.
     *
     * @throws IllegalStateException if the store is full
     */
    boolean rotate(String oldHash, Entry next);

    /** Removes the family's entries and remembers it as revoked, so nothing can join it later. */
    void revokeFamily(String family);

    /** Removes every entry of {@code subject} and returns the families that held them. */
    Set<String> revokeSubject(String subject);

    /**
     * A bounded in-memory store. At most 20 live tokens per subject (the subject's oldest goes
     * first); when {@code maxEntries} is reached, expired entries go first, then used-token
     * records, and only then new issues fail.
     */
    static RefreshStore inMemory(int maxEntries) {
        return new MemoryRefreshStore(maxEntries);
    }
}
