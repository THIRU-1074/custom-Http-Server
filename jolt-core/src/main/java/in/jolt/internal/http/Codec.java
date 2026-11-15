package in.jolt.internal.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Percent-decoding for paths and query strings. */
public final class Codec {

    private Codec() {}

    /**
     * Decodes {@code %XX} escapes as UTF-8, optionally treating {@code +} as a space.
     *
     * @throws IllegalArgumentException on a malformed escape
     */
    public static String percentDecode(String s, boolean plusAsSpace) {
        return percentDecode(s, plusAsSpace, StandardCharsets.UTF_8);
    }

    /** As {@link #percentDecode(String, boolean)} but reads the escaped bytes as {@code cs}. */
    public static String percentDecode(String s, boolean plusAsSpace, Charset cs) {
        if (s.indexOf('%') < 0 && (!plusAsSpace || s.indexOf('+') < 0)) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new IllegalArgumentException("Bad percent-encoding");
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Bad percent-encoding");
                }
                out.write(hi * 16 + lo);
                i += 2;
            } else if (c == '+' && plusAsSpace) {
                out.write(' ');
            } else {
                byte[] bytes = String.valueOf(c).getBytes(cs);
                if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
                    bytes = s.substring(i, i + 2).getBytes(cs);
                    i++;
                }
                out.writeBytes(bytes);
            }
        }
        return out.toString(cs);
    }
}
