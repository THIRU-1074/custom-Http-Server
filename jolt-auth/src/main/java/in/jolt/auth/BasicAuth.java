package in.jolt.auth;

import in.jolt.Middleware;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.BiPredicate;

/** HTTP Basic authentication backed by a caller-supplied credential check. */
public final class BasicAuth {

    private BasicAuth() {}

    /**
     * Requires {@code Authorization: Basic ...}. {@code verifier} receives the user and password;
     * on success the request attribute {@code user} is set, otherwise the response is 401.
     */
    public static Middleware require(BiPredicate<String, String> verifier) {
        return (req, res, next) -> {
            String user = null;
            String password = null;
            String header = req.header("Authorization");
            if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
                try {
                    String decoded =
                            new String(
                                    Base64.getDecoder().decode(header.substring(6).trim()),
                                    StandardCharsets.UTF_8);
                    int colon = decoded.indexOf(':');
                    if (colon >= 0) {
                        user = decoded.substring(0, colon);
                        password = decoded.substring(colon + 1);
                    }
                } catch (IllegalArgumentException ignored) {
                    // malformed base64 is treated as missing credentials
                }
            }
            if (user == null || !verifier.test(user, password)) {
                res.header("WWW-Authenticate", "Basic realm=\"jolt\"")
                        .status(401)
                        .text("Invalid credentials");
                return;
            }
            req.attr("user", user);
            next.run();
        };
    }
}
