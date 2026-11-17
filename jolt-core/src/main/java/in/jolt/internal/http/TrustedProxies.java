package in.jolt.internal.http;

import in.jolt.JoltConfig;
import in.jolt.Request;
import java.util.List;
import java.util.Map;

/** Peers whose {@code X-Forwarded-*} headers are believed; empty means nobody. */
public final class TrustedProxies {

    private record Net(byte[] address, int bits) {}

    private static final TrustedProxies NONE = new TrustedProxies(List.of());

    private final List<Net> nets;

    private TrustedProxies(List<Net> nets) {
        this.nets = nets;
    }

    /**
     * Compiles addresses and CIDR blocks such as {@code 10.0.0.0/8} or {@code fd00::/8}.
     *
     * @throws IllegalArgumentException for anything that is not an IP literal or valid block
     */
    public static TrustedProxies of(List<String> specs) {
        if (specs.isEmpty()) {
            return NONE;
        }
        return new TrustedProxies(specs.stream().map(TrustedProxies::net).toList());
    }

    public static TrustedProxies from(JoltConfig config) {
        return of(config.trustedProxies());
    }

    /**
     * Builds the request for a connection from {@code peer}. Only when the peer is trusted do the
     * forwarded address, scheme and host replace the peer's own.
     */
    public Request request(
            JoltConfig config,
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            byte[] body,
            String peer) {
        String address = peer;
        String scheme = null;
        String host = null;
        if (!nets.isEmpty() && trusts(peer)) {
            address = forwardedFor(headers.get("X-Forwarded-For"), peer);
            String proto = last(headers.get("X-Forwarded-Proto"));
            if (proto != null
                    && (proto.equalsIgnoreCase("http") || proto.equalsIgnoreCase("https"))) {
                scheme = proto.toLowerCase();
            }
            String fwdHost = last(headers.get("X-Forwarded-Host"));
            if (fwdHost != null && validHost(fwdHost)) {
                host = fwdHost;
            }
        }
        return new Request(
                method,
                path,
                rawQuery,
                headers,
                body,
                config.jsonMapper(),
                address,
                scheme,
                host,
                config);
    }

    private String forwardedFor(String header, String peer) {
        if (header == null) {
            return peer;
        }
        String[] entries = header.split(",", -1);
        String chosen = null;
        for (int i = entries.length - 1; i >= 0; i--) {
            String entry = entries[i].strip();
            byte[] bytes = literal(entry);
            if (bytes == null) {
                return peer;
            }
            chosen = entry;
            if (!trusts(bytes)) {
                break;
            }
        }
        return chosen;
    }

    private boolean trusts(String address) {
        if (address == null) {
            return false;
        }
        int scope = address.indexOf('%');
        byte[] bytes = literal(scope < 0 ? address : address.substring(0, scope));
        return bytes != null && trusts(bytes);
    }

    private boolean trusts(byte[] address) {
        for (Net n : nets) {
            if (n.address.length == address.length && matches(n, address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Net n, byte[] address) {
        int full = n.bits / 8;
        for (int i = 0; i < full; i++) {
            if (n.address[i] != address[i]) {
                return false;
            }
        }
        int rest = n.bits % 8;
        if (rest == 0) {
            return true;
        }
        int mask = 0xFF << (8 - rest) & 0xFF;
        return (n.address[full] & mask) == (address[full] & mask);
    }

    private static Net net(String spec) {
        String addr = spec.strip();
        int slash = addr.indexOf('/');
        String bitsText = slash < 0 ? null : addr.substring(slash + 1);
        byte[] bytes = literal(slash < 0 ? addr : addr.substring(0, slash));
        if (bytes == null) {
            throw new IllegalArgumentException("Not an IP address or CIDR block: " + spec);
        }
        int bits = bytes.length * 8;
        if (bitsText != null) {
            try {
                bits = Integer.parseInt(bitsText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad prefix length in " + spec);
            }
            if (bits < 0
                    || bits > bytes.length * 8
                    || !bitsText.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("Bad prefix length in " + spec);
            }
        }
        return new Net(bytes, bits);
    }

    /**
     * Address bytes of an IPv4 or IPv6 literal (an optional {@code %scope} is ignored), or null.
     * Parsed by hand: no name service is ever consulted.
     */
    static byte[] literal(String s) {
        if (s.indexOf(':') < 0) {
            return ipv4(s);
        }
        int pct = s.indexOf('%');
        String addr = pct < 0 ? s : s.substring(0, pct);
        if (pct >= 0 && !validScope(s.substring(pct + 1))) {
            return null;
        }
        return ipv6(addr);
    }

    private static boolean validScope(String scope) {
        if (scope.isEmpty()) {
            return false;
        }
        for (int i = 0; i < scope.length(); i++) {
            char c = scope.charAt(i);
            boolean ok =
                    c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ipv4(String s) {
        byte[] out = new byte[4];
        int octet = 0;
        int digits = 0;
        int value = 0;
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : '.';
            if (c >= '0' && c <= '9') {
                if (digits > 0 && value == 0 || ++digits > 3) {
                    return null;
                }
                value = value * 10 + (c - '0');
            } else if (c == '.' && digits > 0 && value <= 255 && octet < 4) {
                out[octet++] = (byte) value;
                digits = 0;
                value = 0;
            } else {
                return null;
            }
        }
        return octet == 4 ? out : null;
    }

    private static byte[] ipv6(String s) {
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return null;
        }
        int[] head = groups(dbl < 0 ? s : s.substring(0, dbl), dbl < 0);
        int[] tail = dbl < 0 ? new int[0] : groups(s.substring(dbl + 2), true);
        if (head == null || tail == null) {
            return null;
        }
        int total = head.length + tail.length;
        if (dbl < 0 ? total != 8 : total > 7) {
            return null;
        }
        int[] all = new int[8];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, 8 - tail.length, tail.length);
        boolean mapped = all[5] == 0xFFFF;
        for (int i = 0; i < 5; i++) {
            mapped &= all[i] == 0;
        }
        byte[] out = new byte[mapped ? 4 : 16];
        for (int i = mapped ? 6 : 0; i < 8; i++) {
            int at = mapped ? (i - 6) * 2 : i * 2;
            out[at] = (byte) (all[i] >> 8);
            out[at + 1] = (byte) all[i];
        }
        return out;
    }

    /**
     * Hex groups of one side of an IPv6 address; the last may be a dotted IPv4. Null if invalid.
     */
    private static int[] groups(String part, boolean allowV4Last) {
        if (part.isEmpty()) {
            return new int[0];
        }
        String[] texts = part.split(":", -1);
        int[] out = new int[texts.length + 1];
        int n = 0;
        for (int i = 0; i < texts.length; i++) {
            String t = texts[i];
            if (allowV4Last && i == texts.length - 1 && t.indexOf('.') >= 0) {
                byte[] v4 = ipv4(t);
                if (v4 == null) {
                    return null;
                }
                out[n++] = (v4[0] & 0xFF) << 8 | v4[1] & 0xFF;
                out[n++] = (v4[2] & 0xFF) << 8 | v4[3] & 0xFF;
                break;
            }
            if (t.isEmpty() || t.length() > 4) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < t.length(); j++) {
                int d = Character.digit(t.charAt(j), 16);
                if (d < 0 || t.charAt(j) > 'f') {
                    return null;
                }
                v = v * 16 + d;
            }
            out[n++] = v;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static String last(String header) {
        if (header == null) {
            return null;
        }
        String v = header.substring(header.lastIndexOf(',') + 1).strip();
        return v.isEmpty() ? null : v;
    }

    private static boolean validHost(String h) {
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c <= ' ' || c >= 0x7f || "/\\?#@".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }
}
