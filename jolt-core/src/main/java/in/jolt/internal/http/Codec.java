package in.jolt.internal.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Percent-encoding and decoding for paths and query strings. */
public final class Codec {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Codec() {}

    /**
     * Decodes {@code %XX} escapes as UTF-8, optionally treating {@code +} as a space.
     *
     * @throws IllegalArgumentException on a malformed escape
     */
    public static String percentDecode(String s, boolean plusAsSpace) {
        return percentDecode(s, plusAsSpace, StandardCharsets.UTF_8);
    }

    /**
     * Escapes every UTF-8 byte outside the RFC 3986 unreserved set as {@code %XX}; {@code /} is
     * kept only when {@code keepSlash}.
     */
    public static String percentEncode(String s, boolean keepSlash) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~'
                    || (keepSlash && c == '/')) {
                sb.append(c);
            } else {
                sb.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        }
        return sb.toString();
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
