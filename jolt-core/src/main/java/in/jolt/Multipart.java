package in.jolt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict RFC 7578 parser over an already size-bounded body. */
final class Multipart {

    private static final int MAX_BOUNDARY = 70;
    private static final int MAX_FILENAME = 255;
    private static final String BOUNDARY_CHARS = "'()+_,-./:=? ";

    private final byte[] body;
    private final byte[] delimiter;
    private final JoltConfig config;

    private Multipart(byte[] body, String boundary, JoltConfig config) {
        this.body = body;
        this.delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        this.config = config;
    }

    static List<Part> parse(String contentType, byte[] body, JoltConfig config) {
        String boundary = boundary(contentType);
        if (body.length > config.maxBodyBytes()) {
            throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "Body too large");
        }
        return new Multipart(body, boundary, config).parts();
    }

    private static String boundary(String contentType) {
        List<String> params = splitParams(contentType);
        for (int i = 1; i < params.size(); i++) {
            String p = params.get(i);
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).strip().equalsIgnoreCase("boundary")) {
                String v = unquote(p.substring(eq + 1).strip());
                if (v == null || v.isEmpty() || v.length() > MAX_BOUNDARY || !validBoundary(v)) {
                    break;
                }
                return v;
            }
        }
        throw bad("Missing or invalid multipart boundary");
    }

    private static boolean validBoundary(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok =
                    c >= '0' && c <= '9'
                            || c >= 'a' && c <= 'z'
                            || c >= 'A' && c <= 'Z'
                            || BOUNDARY_CHARS.indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return v.charAt(v.length() - 1) != ' ';
    }

    private List<Part> parts() {
        List<Part> out = new ArrayList<>();
        int pos = firstDelimiter();
        while (true) {
            if (startsWith(pos, "--")) {
                return List.copyOf(out);
            }
            if (!startsWith(pos, "\r\n")) {
                throw bad("Malformed multipart delimiter");
            }
            if (out.size() >= config.maxParts()) {
                throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "Too many parts");
            }
            int start = pos + 2;
            int end = indexOf(delimiter, start);
            if (end < 0) {
                throw bad("Unterminated multipart body");
            }
            out.add(part(start, end));
            pos = end + delimiter.length;
        }
    }

    private int firstDelimiter() {
        int dashes = delimiter.length - 2;
        boolean atStart = body.length >= dashes;
        for (int i = 0; atStart && i < dashes; i++) {
            atStart = body[i] == delimiter[i + 2];
        }
        if (atStart) {
            return dashes;
        }
        int at = indexOf(delimiter, 0);
        if (at < 0) {
            throw bad("Multipart boundary not found");
        }
        return at + delimiter.length;
    }

    private Part part(int start, int end) {
        int headerEnd;
        int contentStart;
        if (startsWith(start, "\r\n")) {
            headerEnd = start;
            contentStart = start + 2;
        } else {
            headerEnd = indexOf(new byte[] {'\r', '\n', '\r', '\n'}, start);
            if (headerEnd < 0 || headerEnd > end) {
                throw bad("Malformed multipart part headers");
            }
            contentStart = headerEnd + 4;
        }
        if (end < contentStart) {
            throw bad("Malformed multipart part");
        }
        if (headerEnd - start > config.maxHeaderBytes()) {
            throw bad("Part headers too large");
        }
        if (end - contentStart > config.maxPartBytes()) {
            throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "Part too large");
        }
        String name = null;
        String filename = null;
        String type = null;
        boolean disposition = false;
        String headers = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
        for (String line : headers.isEmpty() ? new String[0] : headers.split("\r\n", -1)) {
            int colon = line.indexOf(':');
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw bad("Malformed part header");
            }
            noControls(line);
            String header = line.substring(0, colon);
            String value = line.substring(colon + 1).strip();
            if (header.equalsIgnoreCase("Content-Disposition")) {
                List<String> params = splitParams(value);
                if (!params.get(0).equalsIgnoreCase("form-data")) {
                    throw bad("Part is not form-data");
                }
                disposition = true;
                for (int i = 1; i < params.size(); i++) {
                    String p = params.get(i);
                    int eq = p.indexOf('=');
                    if (eq <= 0) {
                        throw bad("Malformed Content-Disposition");
                    }
                    String key = p.substring(0, eq).strip().toLowerCase();
                    String v = unquote(p.substring(eq + 1).strip());
                    if (v == null) {
                        throw bad("Malformed Content-Disposition");
                    }
                    if (key.equals("name")) {
                        name = v;
                    } else if (key.equals("filename")) {
                        filename = cleanFilename(v);
                    }
                }
            } else if (header.equalsIgnoreCase("Content-Type")) {
                type = value;
            }
        }
        if (!disposition || name == null) {
            throw bad("Part needs Content-Disposition with a name");
        }
        if (type == null || type.isEmpty()) {
            type = filename == null ? "text/plain" : "application/octet-stream";
        }
        byte[] content = new byte[end - contentStart];
        System.arraycopy(body, contentStart, content, 0, content.length);
        return new Part(name, filename, type, content);
    }

    static String cleanFilename(String raw) {
        int cut = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String base = raw.substring(cut + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); ) {
            int cp = base.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= ' ' && cp != 0x7f && sb.length() + Character.charCount(cp) <= MAX_FILENAME) {
                sb.appendCodePoint(cp);
            }
        }
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == '.' || sb.charAt(end - 1) == ' ')) {
            end--;
        }
        String clean = sb.substring(0, end);
        return clean.isEmpty() || reservedDevice(clean) ? "" : clean;
    }

    private static boolean reservedDevice(String name) {
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).stripTrailing().toUpperCase();
        if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX") || stem.equals("NUL")) {
            return true;
        }
        return stem.length() == 4
                && (stem.startsWith("COM") || stem.startsWith("LPT"))
                && stem.charAt(3) >= '1'
                && stem.charAt(3) <= '9';
    }

    private static void noControls(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < ' ' && c != '\t' || c == 0x7f) {
                throw bad("Control character in part header");
            }
        }
    }

    /** Splits on semicolons outside quotes; the first element is the bare value. */
    private static List<String> splitParams(String s) {
        List<String> out = splitParams(s, true);
        if (out == null) {
            out = splitParams(s, false);
        }
        if (out == null) {
            throw bad("Unterminated quoted string");
        }
        return out;
    }

    private static List<String> splitParams(String s, boolean escapes) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean escaped =
                    escapes
                            && quoted
                            && c == '\\'
                            && i + 1 < s.length()
                            && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\\');
            if (escaped) {
                cur.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ';' && !quoted) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        out.add(cur.toString().strip());
        return out;
    }

    /** Returns the token or unescaped quoted string, or null if it is neither. */
    private static String unquote(String v) {
        if (v.startsWith("\"")) {
            if (v.length() < 2 || !v.endsWith("\"")) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < v.length() - 1; i++) {
                char c = v.charAt(i);
                char next = i + 1 < v.length() - 1 ? v.charAt(i + 1) : 0;
                if (c == '\\' && (next == '"' || next == '\\')) {
                    c = next;
                    i++;
                } else if (c == '"') {
                    return null;
                }
                sb.append(c);
            }
            return sb.toString();
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c <= ' ' || "\"(),/:;<=>?@[\\]{}".indexOf(c) >= 0 && c != '=') {
                return null;
            }
        }
        return v;
    }

    private boolean startsWith(int pos, String s) {
        if (pos + s.length() > body.length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (body[pos + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int indexOf(byte[] needle, int from) {
        outer:
        for (int i = from; i <= body.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (body[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static HttpException bad(String msg) {
        return new HttpException(HttpStatus.BAD_REQUEST, msg);
    }
}
