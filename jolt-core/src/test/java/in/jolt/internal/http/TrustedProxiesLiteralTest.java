package in.jolt.internal.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TrustedProxiesLiteralTest {

    @Test
    void parsesIpv4ByHand() {
        assertArrayEquals(new byte[] {1, 2, 3, (byte) 255}, TrustedProxies.literal("1.2.3.255"));
        assertArrayEquals(new byte[] {0, 0, 0, 0}, TrustedProxies.literal("0.0.0.0"));
    }

    @Test
    void rejectsMalformedIpv4WithoutAnyLookup() {
        for (String bad :
                new String[] {
                    "999.1.1.1",
                    "256.1.1.1",
                    "1..2.3",
                    "1.2.3.",
                    ".1.2.3",
                    "1.2.3",
                    "1.2.3.4.5",
                    "1.2.3.-4",
                    "1.2.3.4x",
                    "1.2.3.0004",
                    "localhost",
                    "example.com",
                    "a.b.c.d",
                    "١.2.3.4"
                }) {
            assertNull(TrustedProxies.literal(bad), bad);
        }
    }

    @Test
    void ipv6NeedsOnlyHexColonsAndDots() {
        assertArrayEquals(new byte[16], TrustedProxies.literal("::"));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, TrustedProxies.literal("::ffff:1.2.3.4"));
        for (String bad :
                new String[] {"::g", "1::2::3", "::1%", "fe80::1%eth0 x", "[::1]", "::1/8"}) {
            assertNull(TrustedProxies.literal(bad), bad);
        }
    }

    @Test
    void ipv6ScopeIdIsTolerated() {
        assertArrayEquals(
                TrustedProxies.literal("fe80::1"), TrustedProxies.literal("fe80::1%eth0"));
    }
}
