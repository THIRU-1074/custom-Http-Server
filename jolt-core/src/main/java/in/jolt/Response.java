package in.jolt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fluent HTTP response. The body is buffered and written once the handler chain returns. */
public final class Response {

    private static final java.util.regex.Pattern COOKIE_ATTRIBUTE =
            java.util.regex.Pattern.compile(
                    "(?i)(?:Path=[!-:<-~]+"
                            + "|Domain=\\.?[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"
                            + "|Max-Age=-?[0-9]+"
                            + "|Expires=[A-Za-z0-9 ,:-]+"
                            + "|SameSite=(?:Strict|Lax|None)"
                            + "|Secure|HttpOnly)");

    private final JsonMapper mapper;
    private final List<Map.Entry<String, String>> headers = new ArrayList<>();
    private int status = 200;
    private byte[] body = new byte[0];
    private boolean sent;
    private boolean typeExplicit;
    private boolean secureCookies;
    private long maxFileBytes = 10L * 1024 * 1024;

    public Response(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Response status(int code) {
        this.status = code;
        return this;
    }

    public Response status(HttpStatus s) {
        return status(s.code());
    }

    /** Sets a header, replacing any existing value for the same name. */
    public Response header(String name, String value) {
        checkHeader(name, value);
        headers.removeIf(h -> h.getKey().equalsIgnoreCase(name));
        typeExplicit |= name.equalsIgnoreCase("Content-Type");
        headers.add(Map.entry(name, value));
        return this;
    }

    /** Adds {@code Secure} to default cookies; the server sets this from the config. */
    public Response secureCookies(boolean on) {
        this.secureCookies = on;
        return this;
    }

    /** Caps the size of files sent by {@code sendFile}; the server sets this from the config. */
    public Response maxFileBytes(long limit) {
        this.maxFileBytes = limit;
        return this;
    }

    /**
     * Adds a {@code Set-Cookie} header with {@code Path=/; HttpOnly; SameSite=Lax}, plus {@code
     * Secure} when configured.
     */
    public Response cookie(String name, String value) {
        return cookie(
                name, value, "Path=/; HttpOnly; SameSite=Lax" + (secureCookies ? "; Secure" : ""));
    }

    /**
     * Adds one {@code Set-Cookie} header with the given attributes.
     *
     * @throws IllegalArgumentException if the name is not an RFC 6265 token, the value is not made
     *     of cookie-octets, or the attributes are not limited to Path, Domain, Max-Age, Expires,
     *     SameSite, Secure and HttpOnly
     */
    public Response cookie(String name, String value, String attributes) {
        checkHeader(name, value);
        if (!isToken(name)) {
            throw new IllegalArgumentException("Illegal character in cookie name");
        }
        if (!isCookieValue(value)) {
            throw new IllegalArgumentException("Illegal character in cookie value");
        }
        String attrs = attributes == null ? "" : attributes.strip();
        if (!attrs.isEmpty()) {
            for (String attr : attrs.split("; ", -1)) {
                if (!COOKIE_ATTRIBUTE.matcher(attr).matches()) {
                    throw new IllegalArgumentException("Unsupported cookie attribute");
                }
            }
        }
        headers.add(
                Map.entry(
                        "Set-Cookie", name + "=" + value + (attrs.isEmpty() ? "" : "; " + attrs)));
        return this;
    }

    public Response json(Object value) {
        return send(mapper.write(value), "application/json; charset=utf-8");
    }

    public Response text(String s) {
        return send(s.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    public Response html(String s) {
        return send(s.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    /** Redirects with 302 Found. */
    public Response redirect(String url) {
        return redirect(302, url);
    }

    /**
     * Redirects with an empty body.
     *
     * @throws IllegalArgumentException if the status is not 301, 302, 303, 307 or 308, or the URL
     *     contains a line break
     */
    public Response redirect(int status, String url) {
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            throw new IllegalArgumentException("Not a redirect status: " + status);
        }
        header("Location", url);
        this.status = status;
        return send(new byte[0], "text/plain; charset=utf-8");
    }

    /** Sends raw bytes; the content type defaults to {@code application/octet-stream}. */
    public Response send(byte[] bytes) {
        return send(bytes, "application/octet-stream");
    }

    /**
     * Sends a file, typed by extension.
     *
     * @throws HttpException 404 if the file does not exist or is not a regular file, 413 if it is
     *     larger than the configured cap
     */
    public Response sendFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Not Found");
            }
            if (Files.size(file) > maxFileBytes) {
                throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large");
            }
            return send(Files.readAllBytes(file), contentType(file));
        } catch (NoSuchFileException e) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Not Found");
        } catch (IOException e) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read file");
        }
    }

    /**
     * Sends {@code relative} resolved under {@code root}; the file must stay inside {@code root}
     * after normalisation and symlink resolution.
     *
     * @throws HttpException 403 if the path escapes the root, 404 if it is not a regular file, is
     *     unreachable or has a dotfile segment, 413 if it exceeds the size cap
     */
    public Response sendFile(Path root, String relative) {
        if (relative.indexOf('\0') >= 0) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        try {
            Path base = root.toRealPath();
            String rel = relative;
            while (rel.startsWith("/") || rel.startsWith("\\")) {
                rel = rel.substring(1);
            }
            Path target = base.resolve(rel).normalize();
            if (!target.startsWith(base)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
            }
            for (Path segment : base.relativize(target)) {
                if (segment.toString().startsWith(".")) {
                    throw new HttpException(HttpStatus.NOT_FOUND, "Not Found");
                }
            }
            Path real = target.toRealPath();
            if (!real.startsWith(base)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
            }
            if (!Files.isRegularFile(real)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Not Found");
            }
            return sendFile(real);
        } catch (java.nio.file.InvalidPathException | IOException e) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    /**
     * Returns a fresh response sharing this mapper and headers, minus the lower-case names given.
     */
    public Response copyHeadersExcept(java.util.Set<String> lowerCaseNames) {
        Response copy = new Response(mapper).secureCookies(secureCookies);
        for (var h : headers) {
            if (!lowerCaseNames.contains(h.getKey().toLowerCase(java.util.Locale.ROOT))) {
                copy.headers.add(h);
            }
        }
        return copy;
    }

    public int statusCode() {
        return status;
    }

    public List<Map.Entry<String, String>> headers() {
        return List.copyOf(headers);
    }

    public byte[] body() {
        return body;
    }

    /** True once a body has been set. */
    public boolean isSent() {
        return sent;
    }

    private static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok =
                    c >= 'a' && c <= 'z'
                            || c >= 'A' && c <= 'Z'
                            || c >= '0' && c <= '9'
                            || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCookieValue(String v) {
        int from = 0;
        int to = v.length();
        if (to >= 2 && v.charAt(0) == '"' && v.charAt(to - 1) == '"') {
            from++;
            to--;
        }
        for (int i = from; i < to; i++) {
            char c = v.charAt(i);
            boolean ok =
                    c == 0x21
                            || c >= 0x23 && c <= 0x2B
                            || c >= 0x2D && c <= 0x3A
                            || c >= 0x3C && c <= 0x5B
                            || c >= 0x5D && c <= 0x7E;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    static void checkHeader(String name, String value) {
        if (name == null || name.isEmpty() || value == null) {
            throw new IllegalArgumentException("Header name and value are required");
        }
        if (!isToken(name)) {
            throw new IllegalArgumentException("Illegal character in header name");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException("Illegal character in header value");
            }
        }
    }

    private Response send(byte[] bytes, String defaultType) {
        body = bytes;
        sent = true;
        if (!typeExplicit) {
            headers.removeIf(h -> h.getKey().equalsIgnoreCase("Content-Type"));
            headers.add(Map.entry("Content-Type", defaultType));
        }
        return this;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1)) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }
}
