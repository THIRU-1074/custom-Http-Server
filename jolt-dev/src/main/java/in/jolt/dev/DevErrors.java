package in.jolt.dev;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Middleware;
import java.util.function.BooleanSupplier;

/**
 * Development error page. It renders details only when the environment variable {@code JOLT_DEV} is
 * set or the system property {@code jolt.dev} is {@code true}; otherwise it rethrows, so production
 * behavior is unchanged.
 */
public final class DevErrors {

    private DevErrors() {}

    /**
     * Renders failures other than {@link HttpException} as an HTML page with the exception and
     * stack trace. Install it first with {@code app.use(DevErrors.page())}.
     */
    public static Middleware page() {
        return page(null, DevErrors::devMode);
    }

    /**
     * Like {@link #page()}, but also leaves any exception that {@code app} maps with {@link
     * App#onError} to the app, so dev behaves like production for mapped errors.
     */
    public static Middleware page(App app) {
        return page(app, DevErrors::devMode);
    }

    static Middleware page(BooleanSupplier enabled) {
        return page(null, enabled);
    }

    static Middleware page(App app, BooleanSupplier enabled) {
        return (req, res, next) -> {
            try {
                next.run();
            } catch (Exception e) {
                if (!enabled.getAsBoolean()
                        || res.isSent()
                        || e instanceof HttpException
                        || (app != null && app.mapError(e) != null)) {
                    throw e;
                }
                res.status(500).html(render(req.method() + " " + req.path(), e));
            }
        };
    }

    static boolean devMode() {
        return System.getenv("JOLT_DEV") != null || Boolean.getBoolean("jolt.dev");
    }

    private static String render(String request, Exception e) {
        StringBuilder stack = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            stack.append(t == e ? "" : "Caused by: ").append(t).append("\n");
            for (StackTraceElement frame : t.getStackTrace()) {
                stack.append("    at ").append(frame).append("\n");
            }
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>500 "
                + escape(e.getClass().getSimpleName())
                + "</title><style>body{font:15px system-ui,sans-serif;margin:2rem;color:#222}"
                + "h1{color:#b00020;font-size:1.4rem}pre{background:#f5f5f5;padding:1rem;"
                + "overflow:auto;font-size:13px}.m{color:#666}</style></head><body><h1>"
                + escape(e.getClass().getName())
                + "</h1><p>"
                + escape(String.valueOf(e.getMessage()))
                + "</p><p class=\"m\">"
                + escape(request)
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
