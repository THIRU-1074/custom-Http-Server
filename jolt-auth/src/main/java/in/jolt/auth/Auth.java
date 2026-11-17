package in.jolt.auth;

import in.jolt.Middleware;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Role checks for requests already authenticated by {@link JwtAuth#middleware()} or {@link
 * BasicAuth#require(java.util.function.BiPredicate, java.util.function.Function)}. Plain Basic auth
 * carries no roles. Install after the authentication middleware, e.g. {@code app.use("/admin",
 * ...)}.
 */
public final class Auth {

    private Auth() {}

    /** Passes when the user has at least one of {@code roles}. */
    public static Middleware requireRole(String... roles) {
        return check(roles, false);
    }

    /** Passes when the user has every one of {@code roles}. */
    public static Middleware requireAll(String... roles) {
        return check(roles, true);
    }

    private static Middleware check(String[] required, boolean all) {
        if (required.length == 0) {
            throw new IllegalArgumentException("At least one role is required");
        }
        Set<String> need = Set.copyOf(java.util.Arrays.asList(required));
        return (req, res, next) -> {
            if (req.attr("user") == null) {
                res.header("WWW-Authenticate", "Bearer")
                        .status(401)
                        .text("Authentication required");
                return;
            }
            Set<String> have = rolesOf(req.attr("claims"));
            boolean ok = all ? have.containsAll(need) : need.stream().anyMatch(have::contains);
            if (!ok) {
                res.status(403).text("Forbidden");
                return;
            }
            next.run();
        };
    }

    private static Set<String> rolesOf(Object claims) {
        Set<String> out = new HashSet<>();
        if (claims instanceof Map<?, ?> m) {
            add(out, m.get("role"));
            add(out, m.get("roles"));
        }
        return out;
    }

    private static void add(Set<String> out, Object value) {
        if (value instanceof String s) {
            for (String part : s.split("[\\s,]+")) {
                if (!part.isBlank()) {
                    out.add(part);
                }
            }
        } else if (value instanceof Iterable<?> items) {
            items.forEach(o -> add(out, o));
        }
    }
}
