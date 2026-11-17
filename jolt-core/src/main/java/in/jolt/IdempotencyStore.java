package in.jolt;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Remembers responses by idempotency key. Implementations must make {@link #begin} atomic: of
 * concurrent calls for one key, exactly one gets {@link State#STARTED}.
 */
public interface IdempotencyStore {

    /**
     * Claims {@code key} for a request with this fingerprint. Returns {@code STARTED} for the
     * caller that must run the request and then call {@link #complete} or {@link #release}, {@code
     * IN_FLIGHT} while that request runs, {@code COMPLETED} with the stored response for a repeat,
     * and {@code MISMATCH} when the key belongs to a different fingerprint.
     */
    Claim begin(String key, String fingerprint);

    /** Stores the response of the request that holds {@code key}. */
    void complete(String key, Stored response);

    /** Frees {@code key} held by a request whose response must not be stored. */
    void release(String key);

    enum State {
        STARTED,
        IN_FLIGHT,
        COMPLETED,
        MISMATCH
    }

    /** The outcome of {@link #begin}; {@code response} is set only when {@code COMPLETED}. */
    record Claim(State state, Stored response) {
        static final Claim STARTED = new Claim(State.STARTED, null);
        static final Claim IN_FLIGHT = new Claim(State.IN_FLIGHT, null);
        static final Claim MISMATCH = new Claim(State.MISMATCH, null);
    }

    /** A replayable response: status, the headers worth keeping, and the body bytes. */
    record Stored(int status, List<Map.Entry<String, String>> headers, byte[] body) {}

    /** A bounded in-memory store: 10000 entries, 24 hours. */
    static IdempotencyStore inMemory() {
        return inMemory(10_000, Duration.ofHours(24));
    }

    /**
     * A bounded in-memory store. Completed entries expire after {@code ttl} (checked on access) and
     * the least recently used one is evicted when {@code maxEntries} is exceeded; running entries
     * are never evicted.
     */
    static IdempotencyStore inMemory(int maxEntries, Duration ttl) {
        return new InMemoryIdempotencyStore(maxEntries, ttl, System::nanoTime);
    }
}
