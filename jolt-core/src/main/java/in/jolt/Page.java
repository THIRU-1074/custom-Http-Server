package in.jolt;

import in.jolt.internal.http.Codec;
import in.jolt.internal.http.Values;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A page request: 1-based {@code page} and {@code size}, read from the {@code page} and {@code
 * size} query parameters by {@link #of(Params)}.
 */
public record Page(int page, int size) {

    public Page {
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("page and size must be at least 1");
        }
    }

    /** Page 1 and size 20 by default; size is capped at 100. Bad values are 400. */
    public static Page of(Params params) {
        return of(params, 20, 100);
    }

    /** Like {@link #of(Params)} with the given default size and cap. */
    public static Page of(Params params, int defaultSize, int maxSize) {
        if (defaultSize < 1 || defaultSize > maxSize) {
            throw new IllegalArgumentException("need 1 <= defaultSize <= maxSize");
        }
        int page = atLeastOne(params, "page", 1);
        int size = atLeastOne(params, "size", defaultSize);
        return new Page(page, Math.min(size, maxSize));
    }

    /** Rows to skip: {@code (page - 1) * size}. */
    public long offset() {
        return (page - 1L) * size;
    }

    /** Rows to fetch; the same as {@link #size()}. */
    public int limit() {
        return size;
    }

    /** This page's items with the total row count, ready to send as JSON. */
    public <T> Result<T> result(List<T> items, long total) {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        return new Result<>(items, page, size, total, total / size + (total % size == 0 ? 0 : 1));
    }

    /** JSON shape {@code {items, page, size, total, totalPages}}; totalPages is 0 when total is. */
    public record Result<T>(List<T> items, int page, int size, long total, long totalPages) {

        /** Sets a {@code Link} header (first, prev, next, last) keeping the other query params. */
        public Result<T> link(Request req, Response res) {
            String base = encode(req.path(), true) + "?" + otherParams(req.rawQuery());
            long last = Math.max(totalPages, 1);
            StringBuilder sb = new StringBuilder();
            rel(sb, base, 1, "first");
            if (page > 1) {
                rel(sb, base, page - 1L, "prev");
            }
            if (page < totalPages) {
                rel(sb, base, page + 1L, "next");
            }
            rel(sb, base, last, "last");
            res.header("Link", sb.toString());
            return this;
        }

        private void rel(StringBuilder sb, String base, long n, String rel) {
            sb.append(sb.isEmpty() ? "" : ", ")
                    .append('<')
                    .append(base)
                    .append("page=")
                    .append(n)
                    .append("&size=")
                    .append(size)
                    .append(">; rel=\"")
                    .append(rel)
                    .append('"');
        }
    }

    private static int atLeastOne(Params params, String name, int fallback) {
        int n = Values.convert("query", name, params.query(name), Integer.class, fallback);
        if (n < 1) {
            throw new HttpException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid query parameter '" + name + "': must be at least 1");
        }
        return n;
    }

    private static String otherParams(String rawQuery) {
        StringBuilder sb = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k;
            String v;
            try {
                k = Codec.percentDecode(eq < 0 ? pair : pair.substring(0, eq), true);
                v = eq < 0 ? null : Codec.percentDecode(pair.substring(eq + 1), true);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (k.isEmpty() || k.equals("page") || k.equals("size")) {
                continue;
            }
            sb.append(encode(k, false));
            if (v != null) {
                sb.append('=').append(encode(v, false));
            }
            sb.append('&');
        }
        return sb.toString();
    }

    private static String encode(String s, boolean path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean plain =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '-'
                            || c == '.'
                            || c == '_'
                            || c == '~'
                            || (path && c == '/');
            if (plain) {
                sb.append(c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4 & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
