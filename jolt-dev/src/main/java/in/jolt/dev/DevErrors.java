package in.jolt.dev;

import in.jolt.HttpException;
import in.jolt.Middleware;

/** Development error page. Never installed by default; it exposes stack traces. */
public final class DevErrors {

    private DevErrors() {}

    /**
     * Renders failures as an HTML page with the exception and stack trace. Install it first with
     * {@code app.use(DevErrors.page())}, and only in development.
     */
    public static Middleware page() {
        return (req, res, next) -> {
            try {
                next.run();
            } catch (Exception e) {
                if (res.isSent()) throw e;
                int status = e instanceof HttpException he ? he.status() : 500;
                res.status(status).html(render(req.method() + " " + req.path(), status, e));
            }
        };
    }

    private static String render(String request, int status, Exception e) {
        StringBuilder stack = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            stack.append(t == e ? "" : "Caused by: ").append(t).append("\n");
            for (StackTraceElement frame : t.getStackTrace()) {
                stack.append("    at ").append(frame).append("\n");
            }
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>"
                + status
                + " "
                + escape(e.getClass().getSimpleName())
                + "</title><style>body{font:15px system-ui,sans-serif;margin:2rem;color:#222}"
                + "h1{color:#b00020;font-size:1.4rem}pre{background:#f5f5f5;padding:1rem;"
                + "overflow:auto;font-size:13px}.m{color:#666}</style></head><body><h1>"
                + escape(e.getClass().getName())
                + "</h1><p>"
                + escape(String.valueOf(e.getMessage()))
                + "</p><p class=\"m\">"
                + escape(request)
                + " &middot; status "
                + status
                + "</p><pre>"
                + escape(stack.toString())
                + "</pre></body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
