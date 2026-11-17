package in.jolt;

import in.jolt.internal.http.ConnectionHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * A WebSocket endpoint registered with {@link Router#ws}; configure it before the server starts.
 */
public final class WebSocketRoute {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final WebSocketHandler handler;
    private volatile List<String> origins = List.of();

    WebSocketRoute(WebSocketHandler handler) {
        this.handler = handler;
    }

    /**
     * Accepts only these {@code Origin} values (for example {@code https://app.example}). Without
     * this, a request with an {@code Origin} header must have the same host as its {@code Host}
     * header; requests without {@code Origin} (non-browser clients) are always accepted.
     */
    public WebSocketRoute origins(String... allowed) {
        this.origins = List.of(allowed);
        return this;
    }

    void handshake(Request req, Response res) throws IOException {
        if (!hasToken(req.header("Upgrade"), "websocket")) {
            res.header("Upgrade", "websocket");
            throw new HttpException(HttpStatus.UPGRADE_REQUIRED, "Upgrade Required");
        }
        if (!hasToken(req.header("Connection"), "upgrade")) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Connection must include Upgrade");
        }
        if (!"13".equals(req.header("Sec-WebSocket-Version"))) {
            res.header("Sec-WebSocket-Version", "13");
            throw new HttpException(HttpStatus.UPGRADE_REQUIRED, "Unsupported WebSocket version");
        }
        String key = req.header("Sec-WebSocket-Key");
        if (!validKey(key)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid Sec-WebSocket-Key");
        }
        if (!originAllowed(req)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Origin not allowed");
        }
        ConnectionHandler host = res.host();
        if (host == null) {
            throw new HttpException(HttpStatus.NOT_IMPLEMENTED, "WebSocket needs a live server");
        }
        host.take();
        String head =
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: "
                        + accept(key)
                        + "\r\n\r\n";
        host.out().write(head.getBytes(StandardCharsets.ISO_8859_1));
        host.out().flush();
        new WebSocketSession(req, host, handler).run();
    }

    private boolean originAllowed(Request req) {
        String origin = req.header("Origin");
        if (origin == null) {
            return true;
        }
        List<String> allowed = origins;
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(origin::equalsIgnoreCase);
        }
        int scheme = origin.indexOf("://");
        return scheme >= 0 && origin.substring(scheme + 3).equalsIgnoreCase(req.host());
    }

    private static boolean hasToken(String header, String token) {
        if (header == null) {
            return false;
        }
        for (String part : header.split(",")) {
            if (part.strip().toLowerCase(Locale.ROOT).equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validKey(String key) {
        if (key == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String accept(String key) {
        try {
            byte[] sha =
                    MessageDigest.getInstance("SHA-1")
                            .digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(sha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
