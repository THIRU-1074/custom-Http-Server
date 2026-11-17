package in.jolt;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Remembers responses by idempotency key. {@link #begin} must be atomic: of concurrent calls for
 * one key exactly one gets {@code STARTED} and must then call {@link #complete} or {@link
 * #release}. Return {@code FULL} when no room can be made, {@code MISMATCH} for another
 * fingerprint, {@code IN_FLIGHT} while the first runs and {@code COMPLETED} with the response for a
 * repeat. Build results with the {@link Claim} factories.
 */
public interface IdempotencyStore {

    Claim begin(Key key, String fingerprint);

    /** Stores the response of the request that holds {@code key}. */
    void complete(Key key, Stored response);

    /** Frees {@code key} held by a request whose response must not be stored. */
    void release(Key key);

    enum State {
        STARTED,
        IN_FLIGHT,
        COMPLETED,
        MISMATCH,
        FULL
    }

    /** A client scope (user, credential hash or address) and the client's idempotency key. */
    record Key(String scope, String id) {}

    /** The outcome of {@link #begin}; {@code response} is set only when {@code COMPLETED}. */
    record Claim(State state, Stored response) {
        private static final Claim STARTED = new Claim(State.STARTED, null);
        private static final Claim IN_FLIGHT = new Claim(State.IN_FLIGHT, null);
        private static final Claim MISMATCH = new Claim(State.MISMATCH, null);
        private static final Claim FULL = new Claim(State.FULL, null);

        public static Claim started() {
            return STARTED;
        }

        public static Claim inFlight() {
            return IN_FLIGHT;
        }

        public static Claim mismatch() {
            return MISMATCH;
        }

        public static Claim full() {
            return FULL;
        }

        public static Claim completed(Stored response) {
            return new Claim(State.COMPLETED, response);
        }
    }

    /** A replayable response: status, headers and body bytes. */
    record Stored(int status, List<Map.Entry<String, String>> headers, byte[] body) {}

    /** The in-memory default: 10000 entries, 1000 per scope, 24 hours, 5 minutes in flight. */
    static IdempotencyStore inMemory() {
        return inMemory(10_000, 1000, Duration.ofHours(24), Duration.ofMinutes(5));
    }

    /** As {@link #inMemory(int, int, Duration, Duration)} with 1000 entries per scope. */
    static IdempotencyStore inMemory(int maxEntries, Duration ttl) {
        return inMemory(maxEntries, 1000, ttl, Duration.ofMinutes(5));
    }

    /**
     * A bounded in-memory store. Completed entries expire after {@code ttl}, a running one after
     * {@code inFlightTimeout} (then its key is free again). Over {@code perScope} a scope loses its
     * oldest completed entry; over {@code maxEntries} the largest scope does. Running entries are
     * never evicted, so a store full of them answers {@code FULL}.
     */
    static IdempotencyStore inMemory(
            int maxEntries, int perScope, Duration ttl, Duration inFlightTimeout) {
        return new InMemoryIdempotencyStore(
                maxEntries, perScope, ttl, inFlightTimeout, System::nanoTime);
    }
}
