package in.jolt.internal.http;

import in.jolt.JoltConfig;
import in.jolt.Request;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

    public boolean isEmpty() {
        return nets.isEmpty();
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
                method, path, rawQuery, headers, body, config.jsonMapper(), address, scheme, host);
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

    /** Address bytes of an IPv4 or IPv6 literal, or null. Never resolves names. */
    private static byte[] literal(String s) {
        if (s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean colon = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') {
                colon = true;
            } else if (c != '.' && (c > 'f' || Character.digit(c, 16) < 0)) {
                return null;
            }
        }
        if (!colon && s.chars().anyMatch(c -> c != '.' && (c < '0' || c > '9'))) {
            return null;
        }
        if (!colon && s.split("\\.", -1).length != 4) {
            return null;
        }
        try {
            return InetAddress.getByName(s).getAddress();
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
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
