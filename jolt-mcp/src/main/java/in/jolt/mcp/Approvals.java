package in.jolt.mcp;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Pending approvals of an {@link Mcp} endpoint. Each id is single use, bound to one agent, tool and
 * argument set, and expires after 10 minutes. In memory, per process.
 */
public final class Approvals {

    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_PENDING = 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum State {
        PENDING,
        APPROVED,
        DENIED
    }

    private static final class Entry {
        final String agent;
        final String tool;
        final String argsHash;
        final long expires;
        State state = State.PENDING;
        String reason;

        Entry(String agent, String tool, String argsHash, long expires) {
            this.agent = agent;
            this.tool = tool;
            this.argsHash = argsHash;
            this.expires = expires;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier nanos;

    Approvals(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /** Approves a pending call; false if the id is unknown, expired or already decided. */
    public boolean approve(String id) {
        return decide(id, State.APPROVED, null);
    }

    /** Denies a pending call; the agent's retry gets {@code reason}. False as for approve. */
    public boolean deny(String id, String reason) {
        return decide(id, State.DENIED, reason == null ? "no reason given" : reason);
    }

    private boolean decide(String id, State state, String reason) {
        boolean[] done = {false};
        entries.computeIfPresent(
                id,
                (k, e) -> {
                    if (expired(e)) {
                        return null;
                    }
                    if (e.state == State.PENDING) {
                        e.state = state;
                        e.reason = reason;
                        done[0] = true;
                    }
                    return e;
                });
        return done[0];
    }

    /** Creates a pending approval and returns its id, or null when too many are pending. */
    synchronized String create(String agent, String tool, String argsHash) {
        if (entries.size() >= MAX_PENDING) {
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                if (expired(it.next())) {
                    it.remove();
                }
            }
            if (entries.size() >= MAX_PENDING) {
                return null;
            }
        }
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        String id = HexFormat.of().formatHex(b);
        entries.put(id, new Entry(agent, tool, argsHash, nanos.getAsLong() + TTL.toNanos()));
        return id;
    }

    /**
     * Consumes an approved id for this exact call. Returns null when the call may run, otherwise
     * the reason it may not; {@code pending[0]} is set when the human has not decided yet.
     */
    String consume(String id, String agent, String tool, String argsHash, boolean[] pending) {
        String[] problem = {"Unknown or expired approvalId " + id};
        entries.computeIfPresent(
                id,
                (k, e) -> {
                    if (expired(e)) {
                        return null;
                    }
                    if (!e.agent.equals(agent)
                            || !e.tool.equals(tool)
                            || !e.argsHash.equals(argsHash)) {
                        problem[0] =
                                "approvalId "
                                        + id
                                        + " was issued for a different agent, tool or arguments";
                        return e;
                    }
                    switch (e.state) {
                        case PENDING -> {
                            pending[0] = true;
                            problem[0] =
                                    "Approval "
                                            + id
                                            + " is still pending; ask the human to approve it";
                            return e;
                        }
                        case DENIED -> {
                            problem[0] = "Approval " + id + " was denied: " + e.reason;
                            return null;
                        }
                        default -> {
                            problem[0] = null;
                            return null;
                        }
                    }
                });
        return problem[0];
    }

    private boolean expired(Entry e) {
        return nanos.getAsLong() - e.expires >= 0;
    }
}
