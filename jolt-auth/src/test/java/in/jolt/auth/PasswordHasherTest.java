package in.jolt.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private static final String PASSWORD = "correct horse";
    private static final String EXAMPLE_HASH =
            "pbkdf2$1000$AAECAwQFBgcICQoLDA0ODw==$yRTMTwbMbo9G0VfjobWqerzuuxe7BETNTErBbKKumGQ=";

    @Test
    void verifiesTheRightPasswordOnly() {
        String hash = PasswordHasher.hash(PASSWORD, 1000);
        assertTrue(PasswordHasher.verify(PASSWORD, hash));
        assertFalse(PasswordHasher.verify("wrong", hash));
    }

    @Test
    void charArrayAndStringAgree() {
        String hash = PasswordHasher.hash(PASSWORD.toCharArray(), 1000);
        assertTrue(PasswordHasher.verify(PASSWORD, hash));
    }

    @Test
    void usesDefaultIterationsAndAFreshSaltPerHash() {
        String a = PasswordHasher.hash(PASSWORD);
        assertNotEquals(a, PasswordHasher.hash(PASSWORD));
        assertTrue(a.startsWith("pbkdf2$210000$"));
        assertEquals(210_000, PasswordHasher.DEFAULT_ITERATIONS);
    }

    @Test
    void acceptsHashesInTheExistingFormat() {
        assertTrue(PasswordHasher.verify(PASSWORD, EXAMPLE_HASH));
    }

    @Test
    void malformedStoredValuesAreRejectedNotThrown() {
        assertFalse(PasswordHasher.verify(PASSWORD, "garbage"));
        assertFalse(PasswordHasher.verify(PASSWORD, "pbkdf2$x$AA==$AA=="));
        assertFalse(PasswordHasher.verify(PASSWORD, "pbkdf2$0$AA==$AA=="));
        assertFalse(PasswordHasher.verify(PASSWORD, "pbkdf2$1000$!!$!!"));
        assertFalse(PasswordHasher.verify(PASSWORD, null));
    }

    @Test
    void needsRehashWhenIterationsAreBelowTheTarget() {
        assertTrue(PasswordHasher.needsRehash(EXAMPLE_HASH));
        assertFalse(PasswordHasher.needsRehash(PasswordHasher.hash(PASSWORD, 210_000)));
        assertFalse(PasswordHasher.needsRehash(EXAMPLE_HASH, 1000));
        assertTrue(PasswordHasher.needsRehash("garbage"));
    }

    @Test
    void nonPositiveIterationsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(PASSWORD, 0));
    }

    @Test
    void nullInputsNeverThrowFromVerify() {
        assertFalse(PasswordHasher.verify(null, EXAMPLE_HASH));
        assertFalse(PasswordHasher.verify(null, null));
    }

    @Test
    void hashRejectsNullWithAClearMessage() {
        var e =
                assertThrows(
                        IllegalArgumentException.class, () -> PasswordHasher.hash((String) null));
        assertEquals("password must not be null", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash((char[]) null));
    }

    @Test
    void absurdStoredIterationCountsAreRejectedWithoutDerivation() {
        String tampered = "pbkdf2$2000000000$AAECAwQFBgcICQoLDA0ODw==$AA==";
        assertFalse(PasswordHasher.verify(PASSWORD, tampered));
        assertTrue(PasswordHasher.needsRehash(tampered));
        assertTrue(PasswordHasher.needsRehash(tampered, 1));
    }
}
