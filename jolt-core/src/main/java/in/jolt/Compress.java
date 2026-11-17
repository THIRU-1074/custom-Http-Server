package in.jolt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Opt-in response compression.
 *
 * <p><b>BREACH:</b> compressing a page that mixes a secret (a CSRF token, a session value) with
 * attacker-controlled text lets an attacker recover the secret from response sizes. Do not enable
 * it for such pages, or mount it only on routes that carry no secrets.
 */
public final class Compress {

    private Compress() {}

    /** Gzips bodies of at least 1024 bytes for clients that accept it. */
    public static Middleware gzip() {
        return gzip(1024);
    }

    /**
     * Gzips bodies of at least {@code minBytes} for clients that accept it. Skips responses that
     * already carry a {@code Content-Encoding}, already-compressed media types, and bodies that do
     * not shrink. Adds {@code Vary: Accept-Encoding} to every eligible response.
     */
    public static Middleware gzip(int minBytes) {
        return (req, res, next) -> {
            next.run();
            int status = res.statusCode();
            byte[] body = res.body();
            if (status < 200
                    || status == 204
                    || status == 206
                    || status == 304
                    || body.length < minBytes
                    || res.headerValue("Content-Encoding") != null
                    || precompressed(res.headerValue("Content-Type"))) {
                return;
            }
            res.addVary("Accept-Encoding");
            if (!acceptsGzip(req.header("Accept-Encoding"))) {
                return;
            }
            byte[] packed = pack(body);
            if (packed.length >= body.length) {
                return;
            }
            res.replaceBody(packed).header("Content-Encoding", "gzip");
            String etag = res.headerValue("ETag");
            if (etag != null && !etag.startsWith("W/")) {
                res.header("ETag", "W/" + etag);
            }
        };
    }

    private static byte[] pack(byte[] body) {
        var out = new ByteArrayOutputStream(body.length / 2 + 32);
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static boolean acceptsGzip(String header) {
        if (header == null) {
            return false;
        }
        boolean star = false;
        for (String part : header.split(",")) {
            String[] p = part.split(";");
            String coding = p[0].strip().toLowerCase(Locale.ROOT);
            double q = 1;
            for (int i = 1; i < p.length; i++) {
                String param = p[i].strip().toLowerCase(Locale.ROOT);
                if (param.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(param.substring(2));
                    } catch (NumberFormatException e) {
                        q = 0;
                    }
                }
            }
            if (coding.equals("gzip") || coding.equals("x-gzip")) {
                return q > 0;
            }
            if (coding.equals("*")) {
                star = q > 0;
            }
        }
        return star;
    }

    private static boolean precompressed(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semi = contentType.indexOf(';');
        String t =
                (semi < 0 ? contentType : contentType.substring(0, semi))
                        .strip()
                        .toLowerCase(Locale.ROOT);
        if (t.equals("image/svg+xml")) {
            return false;
        }
        return t.startsWith("image/")
                || t.startsWith("video/")
                || t.startsWith("audio/")
                || t.startsWith("font/")
                || t.equals("application/zip")
                || t.equals("application/gzip")
                || t.equals("application/x-gzip")
                || t.equals("application/x-bzip2")
                || t.equals("application/x-7z-compressed")
                || t.equals("application/zstd")
                || t.equals("application/pdf");
    }
}
