package in.jolt;

/** Opt-in request logging middleware. */
public final class Logger {

    private Logger() {}

    /**
     * Logs {@code METHOD /path STATUS NNms} at INFO to the {@code in.jolt.requests} System.Logger.
     * Query strings, headers, cookies and bodies are never logged.
     */
    public static Middleware requests() {
        return requests(System.getLogger("in.jolt.requests"));
    }

    static Middleware requests(System.Logger log) {
        return (req, res, next) -> {
            long start = System.nanoTime();
            int status = 500;
            try {
                next.run();
                status = res.statusCode();
            } catch (HttpException e) {
                status = e.status();
                throw e;
            } finally {
                long ms = (System.nanoTime() - start) / 1_000_000;
                log.log(
                        System.Logger.Level.INFO,
                        clean(req.method())
                                + " "
                                + clean(req.path())
                                + " "
                                + status
                                + " "
                                + ms
                                + "ms");
            }
        };
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.appendCodePoint(printable(cp) ? cp : '?'));
        return sb.toString();
    }

    private static boolean printable(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.UNASSIGNED,
                    Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR,
                    Character.SURROGATE ->
                    false;
            default -> true;
        };
    }
}
