package in.jolt;

import in.jolt.IdempotencyStore.Claim;
import in.jolt.IdempotencyStore.Key;
import in.jolt.IdempotencyStore.Stored;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Opt-in {@code Idempotency-Key} support for POST, PUT and PATCH: the first request with a key runs
 * and its response is stored; a repeat with the same request is answered from the store with {@code
 * Idempotency-Replayed: true}. The same key with another method, path, query, content type or body
 * gets 422, a repeat while the first still runs gets 409 with {@code Retry-After}.
 *
 * <p>Not stored, so a retry runs: 5xx, 401, 403, 407, 408, 425 and 429 (they say nothing about the
 * request's own result), thrown handlers, streams and oversized bodies. Keys are scoped to {@code
 * req.attr("user")} when it is text or a number, else to a hash of {@code Authorization}, else to
 * the remote address; install this after authentication. Without any scope the answer is 400.
 */
public final class Idempotency implements Middleware {

    private static final String HEADER = "Idempotency-Key";
    private static final String REPLAYED = "Idempotency-Replayed";
    private static final int MAX_KEY = 128;
    private static final String STORE_ONLY =
            "ttl applies to the default store only; configure it on your store";
    private static final Set<String> SKIPPED =
            Set.of(
                    "set-cookie",
                    "date",
                    "content-length",
                    "transfer-encoding",
                    "connection",
                    "keep-alive",
                    "idempotency-replayed");

    private IdempotencyStore store = IdempotencyStore.inMemory();
    private boolean custom;
    private Duration ttl;
    private Duration inFlight;
    private Set<String> methods = Set.of("POST", "PUT", "PATCH");
    private Function<Request, String> scope = Idempotency::defaultScope;
    private int maxBodyBytes = 1024 * 1024;
    private boolean required;

    private Idempotency() {}

    public static Idempotency keys() {
        return new Idempotency();
    }

    /**
     * Uses {@code store} instead of the in-memory default.
     *
     * @throws IllegalStateException if {@link #ttl} or {@link #inFlightTimeout} was set
     */
    public Idempotency store(IdempotencyStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }
        if (ttl != null || inFlight != null) {
            throw new IllegalStateException(STORE_ONLY);
        }
        this.store = store;
        this.custom = true;
        return this;
    }

    /**
     * Sets how long the default store keeps a response (24 hours).
     *
     * @throws IllegalStateException if a custom {@link #store} is set
     */
    public Idempotency ttl(Duration ttl) {
        Duration valid = positive(ttl, "ttl");
        checkDefaultStore();
        this.ttl = valid;
        return rebuild();
    }

    /**
     * Sets how long the default store treats a request as running (5 minutes); after that its key
     * is free again.
     *
     * @throws IllegalStateException if a custom {@link #store} is set
     */
    public Idempotency inFlightTimeout(Duration timeout) {
        Duration valid = positive(timeout, "inFlightTimeout");
        checkDefaultStore();
        this.inFlight = valid;
        return rebuild();
    }

    /** Derives the client scope of a key; {@code null} answers 400. Replaces the default. */
    public Idempotency scope(Function<Request, String> scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        this.scope = scope;
        return this;
    }

    /** Replaces the methods that honour the header; the default is POST, PUT and PATCH. */
    public Idempotency methods(String... methods) {
        if (methods == null
                || methods.length == 0
                || Arrays.stream(methods).anyMatch(m -> m == null || m.isBlank())) {
            throw new IllegalArgumentException("methods must be non-blank");
        }
        this.methods =
                Arrays.stream(methods)
                        .map(m -> m.strip().toUpperCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
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

    private static Duration positive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return d;
    }

    private void checkDefaultStore() {
        if (custom) {
            throw new IllegalStateException(STORE_ONLY);
        }
    }

    private Idempotency rebuild() {
        store =
                IdempotencyStore.inMemory(
                        10_000,
                        1000,
                        ttl != null ? ttl : Duration.ofHours(24),
                        inFlight != null ? inFlight : Duration.ofMinutes(5));
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
        String who = scope.apply(req);
        if (who == null) {
            res.status(400).text("Idempotency needs an authenticated or addressable client");
            return;
        }
        Key key = new Key(who, given);
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
            case FULL ->
                    res.status(503)
                            .header("Retry-After", "1")
                            .text("Too many idempotent requests in progress");
            case STARTED -> run(key, res, next);
        }
    }

    private void run(Key key, Response res, Next next) throws Exception {
        boolean stored = false;
        try {
            next.run();
            if (storable(res.statusCode())
                    && !res.isStreaming()
                    && res.body().length <= maxBodyBytes) {
                store.complete(key, snapshot(res));
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(key);
            }
        }
    }

    private static boolean storable(int status) {
        return status < 500
                && status != 401
                && status != 403
                && status != 407
                && status != 408
                && status != 425
                && status != 429;
    }

    private static Stored snapshot(Response res) {
        List<Map.Entry<String, String>> kept = new ArrayList<>();
        for (var h : res.headers()) {
            if (!SKIPPED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
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
        if (s.body().length > 0) {
            res.send(s.body());
        }
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

    private static String defaultScope(Request req) {
        Object user = req.attr("user");
        if (user instanceof CharSequence || user instanceof Number) {
            return "u:" + user;
        }
        String auth = req.header("Authorization");
        if (auth != null) {
            return "h:"
                    + HexFormat.of()
                            .formatHex(sha256().digest(auth.getBytes(StandardCharsets.UTF_8)));
        }
        String addr = req.remoteAddress();
        return addr == null ? null : "a:" + addr;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fingerprint(Request req) {
        MessageDigest md = sha256();
        for (String part :
                new String[] {
                    req.method(), req.path(), req.rawQuery(), req.header("Content-Type")
                }) {
            md.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        md.update(req.body());
        return HexFormat.of().formatHex(md.digest());
    }
}
