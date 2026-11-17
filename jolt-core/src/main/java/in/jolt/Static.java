package in.jolt;

import java.nio.file.Path;

/**
 * Static file middleware. Serves {@code index.html} for directories, single-range requests and
 * conditional requests; see {@link Response#sendFile(Path, String)} for the path rules.
 */
public final class Static implements Middleware {

    private final String prefix;
    private final Path root;
    private final String spaIndex;

    private Static(String prefix, Path root, String spaIndex) {
        this.prefix = prefix;
        this.root = root;
        this.spaIndex = spaIndex;
    }

    /** Serves GET and HEAD requests from files under {@code root}, mapping the request path. */
    public static Static serve(Path root) {
        return serve("", root);
    }

    /**
     * Serves files under {@code root} for paths below {@code urlPrefix}. Traversal outside the root
     * is answered 403; unknown files fall through to the next handler.
     */
    public static Static serve(String urlPrefix, Path root) {
        String prefix =
                urlPrefix.endsWith("/")
                        ? urlPrefix.substring(0, urlPrefix.length() - 1)
                        : urlPrefix;
        return new Static(prefix, root, null);
    }

    /**
     * Opt-in single-page-app fallback: a browser navigation (GET or HEAD accepting {@code
     * text/html}, last path segment without a dot) that no file or route answers gets {@code
     * index}, a file under the root. Other misses stay 404.
     */
    public Static spaFallback(String index) {
        return new Static(prefix, root, index);
    }

    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        String path = req.path();
        boolean read = req.method().equals("GET") || req.method().equals("HEAD");
        boolean inside = path.equals(prefix) || path.startsWith(prefix + "/");
        if (!read || !inside) {
            next.run();
            return;
        }
        String rel = path.substring(prefix.length());
        if (send(res, rel)
                || send(res, rel.endsWith("/") ? rel + "index.html" : rel + "/index.html")) {
            return;
        }
        if (spaIndex == null || !navigation(req, rel)) {
            next.run();
            return;
        }
        try {
            next.run();
        } catch (HttpException e) {
            if (!(e instanceof App.NoRoute) || !send(res, "/" + spaIndex)) {
                throw e;
            }
        }
    }

    private boolean send(Response res, String rel) {
        try {
            res.status(200).sendFile(root, rel);
            return true;
        } catch (HttpException e) {
            if (e.status() != HttpStatus.NOT_FOUND.code()) {
                throw e;
            }
            return false;
        }
    }

    private static boolean navigation(Request req, String rel) {
        String accept = req.header("Accept");
        return accept != null
                && accept.contains("text/html")
                && rel.indexOf('.', rel.lastIndexOf('/') + 1) < 0;
    }
}
