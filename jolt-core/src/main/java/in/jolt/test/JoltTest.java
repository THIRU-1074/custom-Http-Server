package in.jolt.test;

import in.jolt.App;
import in.jolt.HttpStatus;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.internal.http.Codec;
import in.jolt.internal.http.ConnectionHandler;
import in.jolt.internal.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory test client: requests go through the app's routing and middleware, with no socket.
 *
 * <pre>{@code
 * JoltTest.of(app).post("/users").json(Map.of("name", "Ada")).expect(201).body("{\"id\":1}");
 * }</pre>
 *
 * Assertion failures throw {@link AssertionError}, so any test framework reports them.
 */
public final class JoltTest {

    private final App app;
    private final Map<String, String> jar;

    private JoltTest(App app, Map<String, String> jar) {
        this.app = app;
        this.jar = jar;
    }

    public static JoltTest of(App app) {
        return new JoltTest(app, null);
    }

    /**
     * A client with its own cookie jar: cookies the app sets are sent on later calls, and removed
     * by {@code Max-Age=0}. Cookies are matched by name only, as the target is a single host.
     */
    public JoltTest session() {
        if (jar != null) {
            return this;
        }
        return new JoltTest(app, new java.util.concurrent.ConcurrentHashMap<>());
    }

    public Call get(String path) {
        return call("GET", path);
    }

    public Call post(String path) {
        return call("POST", path);
    }

    public Call put(String path) {
        return call("PUT", path);
    }

    public Call patch(String path) {
        return call("PATCH", path);
    }

    public Call delete(String path) {
        return call("DELETE", path);
    }

    public Call head(String path) {
        return call("HEAD", path);
    }

    public Call options(String path) {
        return call("OPTIONS", path);
    }

    public Call call(String method, String path) {
        return new Call(app, jar, method, path);
    }

    /** A request under construction. It is sent by {@link #expect(int)} or {@link #send()}. */
    public static final class Call {
        private final App app;
        private final Map<String, String> jar;
        private final String method;
        private final String path;
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private byte[] body = new byte[0];
        private String remoteAddress = "127.0.0.1";

        private Call(App app, Map<String, String> jar, String method, String path) {
            this.app = app;
            this.jar = jar;
            this.method = method;
            this.path = path;
        }

        public Call header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /** Sets the peer address the handler sees; defaults to {@code 127.0.0.1}. */
        public Call remoteAddress(String address) {
            remoteAddress = address;
            return this;
        }

        /** Sets the body to the JSON form of {@code value} and the content type to JSON. */
        public Call json(Object value) {
            body = app.config().jsonMapper().write(value);
            return header("Content-Type", "application/json");
        }

        public Call text(String s) {
            body = s.getBytes(StandardCharsets.UTF_8);
            return header("Content-Type", "text/plain; charset=utf-8");
        }

        public Call body(byte[] bytes) {
            body = bytes.clone();
            return this;
        }

        /** Sends the request and asserts the status. */
        public Result expect(int status) {
            return send().status(status);
        }

        public Result expect(HttpStatus status) {
            return expect(status.code());
        }

        public Result send() {
            int q = path.indexOf('?');
            String rawPath = q < 0 ? path : path.substring(0, q);
            String rawQuery = q < 0 ? "" : path.substring(q + 1);
            if (!rawPath.startsWith("/")) {
                throw new IllegalArgumentException("Path must start with '/': " + path);
            }
            if (body.length > 0) {
                headers.put("Content-Length", String.valueOf(body.length));
            }
            if (jar != null && !jar.isEmpty() && !headers.containsKey("Cookie")) {
                headers.put(
                        "Cookie",
                        jar.entrySet().stream()
                                .map(c -> c.getKey() + "=" + c.getValue())
                                .collect(java.util.stream.Collectors.joining("; ")));
            }
            var mapper = app.config().jsonMapper();
            var req =
                    new Request(
                            method,
                            Codec.percentDecode(rawPath, false),
                            rawQuery,
                            headers,
                            body,
                            mapper,
                            remoteAddress);
            Response res = ConnectionHandler.respond(app, app.config(), req);
            if (jar != null) {
                res.headers().stream()
                        .filter(h -> h.getKey().equalsIgnoreCase("Set-Cookie"))
                        .forEach(h -> store(jar, h.getValue()));
            }
            return new Result(app, method + " " + path, res);
        }

        private static boolean pastDate(String date) {
            try {
                return java.time.ZonedDateTime.parse(
                                date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .isBefore(java.time.Instant.now());
            } catch (java.time.format.DateTimeParseException e) {
                return false;
            }
        }

        private static void store(Map<String, String> jar, String setCookie) {
            String[] parts = setCookie.split(";");
            int eq = parts[0].indexOf('=');
            if (eq <= 0) {
                return;
            }
            String name = parts[0].substring(0, eq).trim();
            boolean expired = false;
            for (int i = 1; i < parts.length; i++) {
                String a = parts[i].trim().toLowerCase(java.util.Locale.ROOT);
                expired |= a.matches("max-age=\\s*(0|-\\d+)");
                if (a.startsWith("expires=")) {
                    expired |= pastDate(parts[i].trim().substring(8));
                }
            }
            if (expired) {
                jar.remove(name);
            } else {
                jar.put(name, parts[0].substring(eq + 1).trim());
            }
        }
    }

    /** A response with fluent assertions; each assertion returns this result. */
    public static final class Result {
        private static final Object MISSING = new Object();

        private final App app;
        private final String what;
        private final Response res;

        private Result(App app, String what, Response res) {
            this.app = app;
            this.what = what;
            this.res = res;
        }

        public int status() {
            return res.statusCode();
        }

        /** First value of a response header, case-insensitive, or {@code null}. */
        public String header(String name) {
            return res.headers().stream()
                    .filter(h -> h.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        public byte[] bytes() {
            return res.body();
        }

        public String text() {
            return new String(res.body(), StandardCharsets.UTF_8);
        }

        public <T> T json(Class<T> type) {
            return app.config().jsonMapper().read(res.body(), type);
        }

        public Result status(int expected) {
            return check(status() == expected, "status", expected, status());
        }

        public Result header(String name, String expected) {
            return check(expected.equals(header(name)), "header " + name, expected, header(name));
        }

        /** Asserts the body text equals {@code expected}. */
        public Result body(String expected) {
            return check(expected.equals(text()), "body", expected, text());
        }

        public Result bodyContains(String fragment) {
            return check(text().contains(fragment), "body to contain", fragment, text());
        }

        /**
         * The value at a dotted JSON path such as {@code user.tags.0}, or {@code null} if absent.
         */
        public Object jsonField(String path) {
            Object v = lookup(path);
            return v == MISSING ? null : v;
        }

        /** Asserts nothing; true when the path exists, even if its value is JSON null. */
        public boolean jsonHas(String path) {
            return lookup(path) != MISSING;
        }

        /**
         * Asserts the JSON value at {@code path}; numbers compare by value, and a missing path
         * fails even when {@code expected} is null.
         */
        public Result jsonEquals(String path, Object expected) {
            Object actual = lookup(path);
            if (actual == MISSING) {
                throw new AssertionError(
                        what + ": json path " + path + " is missing (expected <" + expected + ">)");
            }
            return check(sameJson(expected, actual), "json " + path, expected, actual);
        }

        private static boolean sameJson(Object expected, Object actual) {
            if (expected instanceof Number n && actual instanceof Number a) {
                if (isFinite(n) && isFinite(a)) {
                    return new java.math.BigDecimal(n.toString())
                                    .compareTo(new java.math.BigDecimal(a.toString()))
                            == 0;
                }
                return Double.compare(n.doubleValue(), a.doubleValue()) == 0;
            }
            return java.util.Objects.equals(expected, actual);
        }

        private static boolean isFinite(Number n) {
            return !(n instanceof Double || n instanceof Float) || Double.isFinite(n.doubleValue());
        }

        private Object lookup(String path) {
            Object cur;
            try {
                cur = Json.parse(text());
            } catch (RuntimeException e) {
                throw new AssertionError(what + ": body is not valid JSON");
            }
            for (String key : path.split("\\.")) {
                if (cur instanceof Map<?, ?> m && m.containsKey(key)) {
                    cur = m.get(key);
                } else if (cur instanceof List<?> l
                        && key.matches("\\d+")
                        && Integer.parseInt(key) < l.size()) {
                    cur = l.get(Integer.parseInt(key));
                } else {
                    return MISSING;
                }
            }
            return cur;
        }

        private Result check(boolean ok, String label, Object expected, Object actual) {
            if (!ok) {
                throw new AssertionError(
                        what
                                + ": expected "
                                + label
                                + " <"
                                + expected
                                + "> but was <"
                                + actual
                                + ">");
            }
            return this;
        }
    }
}
