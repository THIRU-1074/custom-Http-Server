package in.jolt.examples.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MainSecretTest {

    @Test
    void shortSecretFailsWithAClearMessage() {
        var e = assertThrows(IllegalArgumentException.class, () -> Main.resolveSecret("short"));
        assertTrue(e.getMessage().contains("JWT_SECRET"));
        assertTrue(e.getMessage().contains("32"));
    }

    @Test
    void secretLengthIsMeasuredInBytesNotCharacters() {
        assertEquals("é".repeat(16), Main.resolveSecret("é".repeat(16)));
        assertThrows(IllegalArgumentException.class, () -> Main.resolveSecret("é".repeat(15)));
    }

    @Test
    void exactlyThirtyTwoBytesIsAccepted() {
        assertEquals("a".repeat(32), Main.resolveSecret("a".repeat(32)));
    }

    @Test
    void missingOrBlankSecretGetsARandomOne() {
        assertEquals(64, Main.resolveSecret(null).length());
        assertEquals(64, Main.resolveSecret("  ").length());
    }
}
