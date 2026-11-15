package in.jolt.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 and a random salt. Stored values look like {@code
 * pbkdf2$iterations$salt$hash} (Base64), and verification is constant-time.
 */
public final class PasswordHasher {

    /** Iteration count used when none is given. */
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    public static String hash(String password, int iterations) {
        return hash(password.toCharArray(), iterations);
    }

    public static String hash(char[] password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    /** Hashes with a fresh salt; the given array is left untouched. */
    public static String hash(char[] password, int iterations) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$"
                + iterations
                + "$"
                + b64.encodeToString(salt)
                + "$"
                + b64.encodeToString(derive(password, salt, iterations));
    }

    /** Returns false for a wrong password or any malformed stored value; never throws on input. */
    public static boolean verify(String password, String stored) {
        String[] parts = parse(stored);
        if (parts == null) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return iterations > 0
                    && MessageDigest.isEqual(
                            expected, derive(password.toCharArray(), salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean needsRehash(String stored) {
        return needsRehash(stored, DEFAULT_ITERATIONS);
    }

    /** True when {@code stored} is malformed or uses fewer than {@code iterations} iterations. */
    public static boolean needsRehash(String stored, int iterations) {
        String[] parts = parse(stored);
        if (parts == null) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) < iterations;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static String[] parse(String stored) {
        if (stored == null) {
            return null;
        }
        String[] parts = stored.split("\\$");
        return parts.length == 4 && parts[0].equals("pbkdf2") ? parts : null;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
