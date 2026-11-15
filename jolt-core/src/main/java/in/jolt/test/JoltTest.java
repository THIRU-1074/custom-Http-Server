package in.jolt.test;

import in.jolt.App;
import in.jolt.HttpStatus;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.internal.http.Codec;
import in.jolt.internal.http.ConnectionHandler;
import java.nio.charset.StandardCharsets;
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

    private JoltTest(App app) {
        this.app = app;
    }

    public static JoltTest of(App app) {
        return new JoltTest(app);
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
        return new Call(app, method, path);
    }

    /** A request under construction. It is sent by {@link #expect(int)} or {@link #send()}. */
    public static final class Call {
        private final App app;
        private final String method;
        private final String path;
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private byte[] body = new byte[0];
        private String remoteAddress = "127.0.0.1";

        private Call(App app, String method, String path) {
            this.app = app;
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
            var mapper = app.config().jsonMapper();
            var req =
                    new Request(
                            method,
                            Codec.percentDecode(rawPath, false),
                            rawQuery,
                            headers,
                            body,
                            mapper);
            req.remoteAddress(remoteAddress);
            Response res = ConnectionHandler.respond(app, app.config(), req);
            return new Result(app, method + " " + path, res);
        }
    }

    /** A response with fluent assertions; each assertion returns this result. */
    public static final class Result {
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
