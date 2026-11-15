package in.jolt;

/** Opt-in CORS middleware. Nothing is sent unless the app installs it. */
public final class Cors {

    private Cors() {}

    /**
     * Allows one exact origin. Matching requests get {@code Access-Control-Allow-Origin}; preflight
     * requests are answered 204. A wildcard origin is refused.
     */
    public static Middleware allow(String origin) {
        if (origin == null || origin.isBlank() || origin.equals("*")) {
            throw new IllegalArgumentException("CORS needs one explicit origin");
        }
        return (req, res, next) -> {
            res.header("Vary", "Origin");
            if (origin.equals(req.header("Origin"))) {
                res.header("Access-Control-Allow-Origin", origin);
                if (req.method().equals("OPTIONS")
                        && req.header("Access-Control-Request-Method") != null) {
                    res.header("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE")
                            .header("Access-Control-Allow-Headers", "Content-Type, Authorization")
                            .header("Access-Control-Max-Age", "600")
                            .status(204);
                    return;
                }
            }
            next.run();
        };
    }
}
