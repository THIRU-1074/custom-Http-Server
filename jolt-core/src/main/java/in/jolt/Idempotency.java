package in.jolt;

import in.jolt.IdempotencyStore.Claim;
import in.jolt.IdempotencyStore.Stored;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in {@code Idempotency-Key} support for POST, PUT and PATCH: the first request with a key runs
 * and its response is stored; a repeat with the same request is answered from the store with {@code
 * Idempotency-Replayed: true}. The same key with another method, path, query or body gets 422, a
 * repeat while the first still runs gets 409 with {@code Retry-After}. 5xx responses, thrown
 * handlers, streams and oversized bodies are not stored, so a retry can run. Keys are scoped to
 * {@code req.attr("user")} when set, else to the remote address.
 */
public final class Idempotency implements Middleware {

    private static final String HEADER = "Idempotency-Key";
    private static final String REPLAYED = "Idempotency-Replayed";
    private static final int MAX_KEY = 128;
    private static final Set<String> KEPT =
            Set.of("content-type", "content-language", "location", "etag");

    private IdempotencyStore store = IdempotencyStore.inMemory();
    private boolean custom;
    private Set<String> methods = Set.of("POST", "PUT", "PATCH");
    private int maxBodyBytes = 1024 * 1024;
    private boolean required;

    private Idempotency() {}

    public static Idempotency keys() {
        return new Idempotency();
    }

    /** Uses {@code store} instead of the in-memory default (10000 entries, 24 hours). */
    public Idempotency store(IdempotencyStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }
        this.store = store;
        this.custom = true;
        return this;
    }

    /** Sets how long the default store keeps a response; ignored with a custom {@link #store}. */
    public Idempotency ttl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (!custom) {
            store = IdempotencyStore.inMemory(10_000, ttl);
        }
        return this;
    }

    /** Replaces the methods that honour the header; the default is POST, PUT and PATCH. */
    public Idempotency methods(String... methods) {
        if (methods == null || methods.length == 0) {
            throw new IllegalArgumentException("at least one method is required");
        }
        Set<String> set = new java.util.HashSet<>();
        for (String m : methods) {
            set.add(m.toUpperCase(Locale.ROOT));
        }
        this.methods = Set.copyOf(set);
        return this;
    }

    /** Responses with a larger body are not stored; the default is 1 MB. */
    public Idempotency maxBodyBytes(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        this.maxBodyBytes = limit;
        return this;
    }

    /** Answers 400 to listed methods that carry no {@code Idempotency-Key}. */
    public Idempotency required() {
        this.required = true;
        return this;
    }

    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        if (!methods.contains(req.method())) {
            next.run();
            return;
        }
        String given = req.header(HEADER);
        if (given == null) {
            if (required) {
                res.status(400).text("Idempotency-Key header is required");
                return;
            }
            next.run();
            return;
        }
        if (!validKey(given)) {
            res.status(400).text("Idempotency-Key must be 1 to 128 printable ASCII characters");
            return;
        }
        String key = scope(req) + given;
        Claim claim = store.begin(key, fingerprint(req));
        switch (claim.state()) {
            case COMPLETED -> replay(res, claim.response());
            case MISMATCH ->
                    res.status(422)
                            .text("Idempotency-Key was already used with a different request");
            case IN_FLIGHT ->
                    res.status(409)
                            .header("Retry-After", "1")
                            .text("A request with this Idempotency-Key is in progress");
            case STARTED -> run(key, res, next);
        }
    }

    private void run(String key, Response res, Next next) throws Exception {
        boolean stored = false;
        try {
            next.run();
            if (res.statusCode() < 500 && !res.isStreaming() && res.body().length <= maxBodyBytes) {
                store.complete(key, snapshot(res));
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(key);
            }
        }
    }

    private static Stored snapshot(Response res) {
        List<Map.Entry<String, String>> kept = new ArrayList<>(2);
        for (var h : res.headers()) {
            if (KEPT.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                kept.add(h);
            }
        }
        return new Stored(res.statusCode(), List.copyOf(kept), res.body());
    }

    private static void replay(Response res, Stored s) {
        res.status(s.status());
        for (var h : s.headers()) {
            res.header(h.getKey(), h.getValue());
        }
        res.header(REPLAYED, "true");
        res.send(s.body());
    }

    private static boolean validKey(String key) {
        int n = key.length();
        if (n < 1 || n > MAX_KEY) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static String scope(Request req) {
        Object user = req.attr("user");
        String who = user != null ? "u" + user : "a" + req.remoteAddress();
        return who.length() + ":" + who + ":";
    }

    private static String fingerprint(Request req) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(req.method().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(req.path().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(req.rawQuery().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(req.body());
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
