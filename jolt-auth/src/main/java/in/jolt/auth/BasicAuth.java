package in.jolt.auth;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.Middleware;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** HTTP Basic authentication backed by a caller-supplied credential check. */
public final class BasicAuth {

    private BasicAuth() {}

    /**
     * Requires {@code Authorization: Basic ...}. {@code verifier} receives the user and password;
     * on success the request attribute {@code user} is set, otherwise it sets {@code
     * WWW-Authenticate} and throws {@link HttpException} 401.
     */
    public static Middleware require(BiPredicate<String, String> verifier) {
        return require(verifier, null);
    }

    /**
     * Like {@link #require(BiPredicate)}, and also sets the {@code claims} attribute to a map with
     * {@code roles} from {@code roles.apply(user)}, so {@link Auth#requireRole} works after it.
     */
    public static Middleware require(
            BiPredicate<String, String> verifier, Function<String, Collection<String>> roles) {
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
                res.header("WWW-Authenticate", "Basic realm=\"jolt\"");
                throw new HttpException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
            }
            req.attr("user", user);
            if (roles != null) {
                req.attr("claims", Map.of("roles", java.util.List.copyOf(roles.apply(user))));
            }
            next.run();
        };
    }
}
