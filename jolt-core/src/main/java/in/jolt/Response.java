package in.jolt;

import in.jolt.internal.view.Views;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private static final String JSON = "application/json";

    private final JsonMapper mapper;
    private final List<Map.Entry<String, String>> headers = new ArrayList<>();
    private int status = 200;
    private byte[] body = new byte[0];
    private boolean sent;
    private boolean typeExplicit;
    private boolean secureCookies;
    private Request req;
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

    /** Sets a weak {@code ETag} from the body and answers 304 if the request's validators match. */
    public Response etag() {
        String tag = "W/\"" + Long.toHexString(body.length) + "-" + digest(body) + "\"";
        header("ETag", tag);
        if (notModified(null, tag)) {
            status = 304;
            body = new byte[0];
        }
        return this;
    }

    /** Sets {@code Cache-Control: max-age} to the whole seconds of {@code ttl}. */
    public Response cache(Duration ttl) {
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be negative");
        }
        return header("Cache-Control", "max-age=" + ttl.toSeconds());
    }

    /** Sets {@code Cache-Control: no-store}. */
    public Response noStore() {
        return header("Cache-Control", "no-store");
    }

    public Response json(Object value) {
        return send(mapper.write(value), "application/json; charset=utf-8");
    }

    /**
     * Sends {@code value} in the representation the request's {@code Accept} header prefers: JSON
     * unless a registered {@link BodyCodec} fits better; {@code byte[]} is sent as is.
     *
     * @throws HttpException 406 if no representation is acceptable
     */
    public Response send(Object value) {
        if (value instanceof byte[] bytes) {
            return send(bytes);
        }
        List<BodyCodec> codecs = req == null ? List.of() : req.codecs();
        List<String> offers = new ArrayList<>();
        boolean json = codecs.stream().noneMatch(c -> c.mediaType().equalsIgnoreCase(JSON));
        if (json) {
            offers.add(JSON);
        }
        codecs.forEach(c -> offers.add(c.mediaType().toLowerCase(Locale.ROOT)));
        if (!codecs.isEmpty()) {
            addVary("Accept");
        }
        int i = Negotiate.pick(req == null ? null : req.header("Accept"), offers);
        if (i < 0) {
            throw new HttpException(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable");
        }
        if (json && i == 0) {
            return json(value);
        }
        BodyCodec codec = codecs.get(json ? i - 1 : i);
        return send(codec.write(value), codec.mediaType());
    }

    public Response text(String s) {
        return send(s.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    /**
     * Renders the template {@code name} under the {@link App#views(Path) views root} as HTML.
     * {@code {{x}}} is escaped, {@code {{{x}}}} is raw, dotted paths and {@code #each}, {@code #if}
     * and {@code {{> partial}}} are supported.
     *
     * @throws IllegalStateException if no views root is configured
     * @throws IllegalArgumentException if the template is missing, malformed or outside the root
     */
    public Response render(String name, Map<String, ?> model) {
        Views views = req == null ? null : req.views();
        if (views == null) {
            throw new IllegalStateException("No views root: call app.views(root)");
        }
        return html(views.render(name, model));
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
            long size = Files.size(file);
            if (size > maxFileBytes) {
                throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large");
            }
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            String tag = "W/\"" + Long.toHexString(size) + "-" + modified.toEpochMilli() + "\"";
            String type = contentType(file);
            if (notModified(modified, tag)) {
                validators(tag, modified);
                status = 304;
                return send(new byte[0], type);
            }
            long[] range = range(size, tag, modified);
            if (range != null && range.length == 0) {
                validators(tag, modified);
                status = 416;
                header("Content-Range", "bytes */" + size);
                return send(new byte[0], type);
            }
            byte[] data =
                    range == null
                            ? Files.readAllBytes(file)
                            : readRange(file, range[0], (int) (range[1] - range[0] + 1));
            validators(tag, modified);
            if (range != null) {
                status = 206;
                header("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + size);
            }
            return send(data, type);
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
        return sendFile(locate(root, relative));
    }

    static Path locate(Path root, String relative) {
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
            return real;
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

    Response request(Request request) {
        this.req = request;
        return this;
    }

    private void validators(String tag, Instant modified) {
        header("ETag", tag);
        header("Last-Modified", HTTP_DATE.format(modified));
        header("Accept-Ranges", "bytes");
    }

    private boolean ifRangeHolds(String tag, Instant modified) {
        String v = req.header("If-Range");
        if (v == null) {
            return true;
        }
        v = v.strip();
        if (v.startsWith("\"") || v.startsWith("W/")) {
            return v.equals(tag);
        }
        try {
            Instant t = DateTimeFormatter.RFC_1123_DATE_TIME.parse(v, Instant::from);
            return !modified.truncatedTo(ChronoUnit.SECONDS).isAfter(t);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private long[] range(long size, String tag, Instant modified) {
        String spec = req == null ? null : req.header("Range");
        if (spec == null
                || status != 200
                || !ifRangeHolds(tag, modified)
                || !(req.method().equals("GET") || req.method().equals("HEAD"))
                || !spec.regionMatches(true, 0, "bytes=", 0, 6)
                || spec.indexOf(',') >= 0) {
            return null;
        }
        String r = spec.substring(6).strip();
        int dash = r.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String from = r.substring(0, dash).strip();
            String to = r.substring(dash + 1).strip();
            if (from.isEmpty()) {
                long n = num(to);
                return n == 0 || size == 0
                        ? new long[0]
                        : new long[] {Math.max(0, size - n), size - 1};
            }
            long start = num(from);
            long end = size - 1;
            if (!to.isEmpty()) {
                long last = num(to);
                if (last < start) {
                    return null;
                }
                end = Math.min(last, end);
            }
            return start >= size ? new long[0] : new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long num(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                throw new NumberFormatException(digits);
            }
        }
        return Long.parseLong(digits);
    }

    private static byte[] readRange(Path file, long start, int length) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(length);
            ch.position(start);
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // keep reading until the range is full or the file ends
            }
            return buf.array();
        }
    }

    private boolean notModified(Instant lastModified, String tag) {
        if (req == null
                || status != 200
                || !(req.method().equals("GET") || req.method().equals("HEAD"))) {
            return false;
        }
        String none = req.header("If-None-Match");
        if (none != null) {
            return matchesTag(none, tag);
        }
        String since = req.header("If-Modified-Since");
        if (since == null || lastModified == null) {
            return false;
        }
        try {
            Instant t = DateTimeFormatter.RFC_1123_DATE_TIME.parse(since, Instant::from);
            return !lastModified.truncatedTo(ChronoUnit.SECONDS).isAfter(t);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean matchesTag(String header, String tag) {
        String mine = tag.startsWith("W/") ? tag.substring(2) : tag;
        for (String candidate : header.split(",")) {
            String c = candidate.strip();
            if (c.equals("*") || (c.startsWith("W/") ? c.substring(2) : c).equals(mine)) {
                return true;
            }
        }
        return false;
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String headerValue(String name) {
        for (var h : headers) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return h.getValue();
            }
        }
        return null;
    }

    Response replaceBody(byte[] bytes) {
        body = bytes;
        return this;
    }

    Response addVary(String token) {
        String existing = headerValue("Vary");
        if (existing == null || existing.isBlank()) {
            return header("Vary", token);
        }
        for (String t : existing.split(",")) {
            if (t.strip().equals("*") || t.strip().equalsIgnoreCase(token)) {
                return this;
            }
        }
        return header("Vary", existing + ", " + token);
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
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1)) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "csv" -> "text/csv; charset=utf-8";
            case "xml" -> "application/xml; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "mp3" -> "audio/mpeg";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "wasm" -> "application/wasm";
            default -> "application/octet-stream";
        };
    }
}
