package in.jolt;

import java.nio.file.Path;

/** Static file middleware. */
public final class Static {

    private Static() {}

    /** Serves GET and HEAD requests from files under {@code root}, mapping the request path. */
    public static Middleware serve(Path root) {
        return serve("", root);
    }

    /**
     * Serves files under {@code root} for paths below {@code urlPrefix}. Traversal outside the root
     * is answered 403; unknown files fall through to the next handler.
     */
    public static Middleware serve(String urlPrefix, Path root) {
        return (req, res, next) -> {
            String path = req.path();
            boolean read = req.method().equals("GET") || req.method().equals("HEAD");
            if (!read || !path.startsWith(urlPrefix)) {
                next.run();
                return;
            }
            try {
                res.sendFile(root, path.substring(urlPrefix.length()));
            } catch (HttpException e) {
                if (e.status() != HttpStatus.NOT_FOUND.code()) {
                    throw e;
                }
                next.run();
            }
        };
    }
}
