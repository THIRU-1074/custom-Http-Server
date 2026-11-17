package in.jolt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opt-in webhook signature check: HMAC-SHA256 over the raw body (or {@code timestamp + "." + body}
 * with {@link #timestampHeader}), hex or base64 in a header, compared in constant time. Any failure
 * is a 401 with one generic message. The body stays readable by the handler.
 */
public final class Webhooks implements Middleware {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32;
    private static final int HEX_LENGTH = MAC_LENGTH * 2;

    private final SecretKeySpec key;
    private final String header;
    private String prefix = "";
    private String timestampHeader;
    private Duration tolerance = Duration.ofMinutes(5);
    private Clock clock = Clock.systemUTC();

    private Webhooks(byte[] secret, String header) {
        if (secret.length < 32) {
            throw new IllegalArgumentException("webhook secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
        this.header = name(header, "signature header");
    }

    /**
     * Verifies the signature in {@code header}.
     *
     * @throws IllegalArgumentException if the secret is under 32 bytes or the header name is blank
     */
    public static Webhooks hmacSha256(byte[] secret, String header) {
        return new Webhooks(Objects.requireNonNull(secret, "secret").clone(), header);
    }

    /** As {@link #hmacSha256(byte[], String)}, with the secret as UTF-8 text. */
    public static Webhooks hmacSha256(String secret, String header) {
        return new Webhooks(
                Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8), header);
    }

    /** Text the header value must start with, such as {@code v1=}; default none. */
    public Webhooks prefix(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        return this;
    }

    /**
     * Requires a unix-seconds timestamp in this header, signs {@code timestamp + "." + body}, and
     * rejects timestamps further than {@link #tolerance} from now, past or future.
     */
    public Webhooks timestampHeader(String name) {
        this.timestampHeader = name(name, "timestamp header");
        return this;
    }

    /** Allowed clock distance when a timestamp header is set; default 5 minutes. */
    public Webhooks tolerance(Duration tolerance) {
        Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.isZero() || tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must be positive");
        }
        this.tolerance = tolerance;
        return this;
    }

    /** Time source for the timestamp check; default UTC system clock. */
    public Webhooks clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        if (!valid(req)) {
            throw new HttpException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        next.run();
    }

    private boolean valid(Request req) {
        String value = req.header(header);
        if (value == null || !value.startsWith(prefix)) {
            return false;
        }
        byte[] given = decode(value.substring(prefix.length()).trim());
        if (given == null) {
            return false;
        }
        byte[] body = req.body();
        if (timestampHeader == null) {
            return MessageDigest.isEqual(mac(body), given);
        }
        String stamp = req.header(timestampHeader);
        if (stamp == null) {
            return false;
        }
        stamp = stamp.trim();
        long timestamp;
        try {
            timestamp = Long.parseLong(stamp);
        } catch (NumberFormatException e) {
            return false;
        }
        byte[] head = (stamp + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] signed = new byte[head.length + body.length];
        System.arraycopy(head, 0, signed, 0, head.length);
        System.arraycopy(body, 0, signed, head.length, body.length);
        boolean match = MessageDigest.isEqual(mac(signed), given);
        long skew = Math.abs(clock.instant().getEpochSecond() - timestamp);
        return match && skew <= tolerance.toSeconds();
    }

    private static byte[] decode(String text) {
        try {
            if (text.length() == HEX_LENGTH) {
                return HexFormat.of().parseHex(text);
            }
            byte[] raw = Base64.getDecoder().decode(text);
            return raw.length == MAC_LENGTH ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] mac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String name(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
