package in.jolt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WebhooksTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String BODY = "{\"id\":\"pay_1\",\"amount\":500}";
    private static final String HEX =
            "adb8689e2cecac87b28419e728cc689c51c8d7f53a2efa1afcbd956d4c093cec";
    private static final String B64 = "rbhonizsrIeyhBnnKMxonFHI1/U6Lvoa/L2VbUwJPOw=";
    private static final String HEX_TS =
            "299246191da6d19ceb78aade06d6d1b2488a57823d1b7d4965ffe3025177347d";
    private static final long NOW = 1_700_000_000L;

    private static Clock at(long seconds) {
        return Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
    }

    private static JoltTest of(Webhooks hook) {
        App app = Jolt.create();
        app.use(hook);
        app.post("/hook", (req, res) -> res.text(req.text() + "|" + req.body().length));
        return JoltTest.of(app);
    }

    private static JoltTest plain() {
        return of(Webhooks.hmacSha256(SECRET, "X-Signature"));
    }

    private static Webhooks stamped(long now) {
        return Webhooks.hmacSha256(SECRET, "X-Signature")
                .timestampHeader("X-Timestamp")
                .clock(at(now));
    }

    private static JoltTest.Call signed(JoltTest t, String sig) {
        return t.post("/hook")
                .header("X-Signature", sig)
                .header("X-Timestamp", String.valueOf(NOW))
                .text(BODY);
    }

    @Test
    void acceptsHexKnownVectorAndKeepsBody() {
        plain().post("/hook")
                .header("X-Signature", HEX)
                .text(BODY)
                .expect(200)
                .body(BODY + "|" + BODY.length());
    }

    @Test
    void acceptsBase64KnownVector() {
        plain().post("/hook").header("X-Signature", B64).text(BODY).expect(200);
    }

    @Test
    void acceptsUppercaseHex() {
        plain().post("/hook").header("X-Signature", HEX.toUpperCase()).text(BODY).expect(200);
    }

    @Test
    void rejectsTamperedBody() {
        plain().post("/hook").header("X-Signature", HEX).text(BODY + " ").expect(401);
    }

    @Test
    void rejectsMissingAndMalformedHeader() {
        plain().post("/hook").text(BODY).expect(401);
        plain().post("/hook").header("X-Signature", "").text(BODY).expect(401);
        plain().post("/hook").header("X-Signature", "zz" + HEX.substring(2)).text(BODY).expect(401);
        plain().post("/hook").header("X-Signature", HEX.substring(1)).text(BODY).expect(401);
    }

    @Test
    void rejectsWrongSecret() {
        byte[] other = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
        of(Webhooks.hmacSha256(other, "X-Signature"))
                .post("/hook")
                .header("X-Signature", HEX)
                .text(BODY)
                .expect(401);
    }

    @Test
    void prefixIsConfigurable() {
        JoltTest t = of(Webhooks.hmacSha256(SECRET, "X-Signature").prefix("v1="));
        t.post("/hook").header("X-Signature", "v1=" + HEX).text(BODY).expect(200);
        t.post("/hook").header("X-Signature", HEX).text(BODY).expect(401);
    }

    @Test
    void signsTimestampDotBody() {
        signed(of(stamped(NOW)), HEX_TS).expect(200);
    }

    @Test
    void bodyOnlySignatureFailsWhenTimestampIsRequired() {
        signed(of(stamped(NOW)), HEX).expect(401);
    }

    @Test
    void rejectsMissingOrBadTimestamp() {
        JoltTest t = of(stamped(NOW));
        t.post("/hook").header("X-Signature", HEX_TS).text(BODY).expect(401);
        t.post("/hook")
                .header("X-Signature", HEX_TS)
                .header("X-Timestamp", "soon")
                .text(BODY)
                .expect(401);
    }

    @Test
    void rejectsStaleAndFutureTimestampsEvenWhenSigned() {
        signed(of(stamped(NOW + 301)), HEX_TS).expect(401);
        signed(of(stamped(NOW - 301)), HEX_TS).expect(401);
    }

    @Test
    void toleranceBoundaryAndOverride() {
        signed(of(stamped(NOW + 300)), HEX_TS).expect(200);
        signed(of(stamped(NOW + 300).tolerance(Duration.ofSeconds(299))), HEX_TS).expect(401);
    }

    @Test
    void failureIsGeneric() {
        var r = plain().post("/hook").header("X-Signature", "00").text(BODY).expect(401);
        assertFalse(r.text().contains("X-Signature"));
    }

    @Test
    void validatesConfiguration() {
        byte[] shortKey = new byte[31];
        assertThrows(
                IllegalArgumentException.class, () -> Webhooks.hmacSha256(shortKey, "X-Signature"));
        assertThrows(
                IllegalArgumentException.class, () -> Webhooks.hmacSha256("short", "X-Signature"));
        assertThrows(IllegalArgumentException.class, () -> Webhooks.hmacSha256(SECRET, " "));
        assertThrows(NullPointerException.class, () -> Webhooks.hmacSha256((byte[]) null, "X"));
        Webhooks w = Webhooks.hmacSha256(SECRET, "X-Signature");
        assertThrows(IllegalArgumentException.class, () -> w.tolerance(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> w.tolerance(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> w.timestampHeader(""));
        assertThrows(NullPointerException.class, () -> w.clock(null));
        assertThrows(NullPointerException.class, () -> w.prefix(null));
    }

    @Test
    void secretIsCopied() {
        byte[] key = SECRET.clone();
        Webhooks w = Webhooks.hmacSha256(key, "X-Signature");
        Arrays.fill(key, (byte) 0);
        of(w).post("/hook").header("X-Signature", HEX).text(BODY).expect(200);
    }
}
