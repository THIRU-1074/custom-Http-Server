import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

/** Runs the same request sequence against a todo app. Usage: java Check.java [baseUrl]. */
public class Check {
    static final HttpClient C = HttpClient.newHttpClient();
    static String base;
    static int failures;

    static int call(String method, String path, boolean auth, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path));
        if (auth) b.header("Authorization", "Bearer secret");
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return C.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static void expect(String name, int want, int got) {
        boolean ok = want == got;
        if (!ok) failures++;
        System.out.printf("  %-4s %-30s want %d got %d%n", ok ? "PASS" : "FAIL", name, want, got);
    }

    public static void main(String[] a) throws Exception {
        base = a.length > 0 ? a[0] : "http://localhost:8080";
        expect("GET /todos without auth", 401, call("GET", "/todos", false, null));
        expect("POST invalid (blank title)", 400, call("POST", "/todos", true, "{\"title\":\"\"}"));
        expect("POST create", 201, call("POST", "/todos", true, "{\"title\":\"a\",\"done\":false}"));
        expect("GET /todos/1", 200, call("GET", "/todos/1", true, null));
        expect("GET /todos/999 missing", 404, call("GET", "/todos/999", true, null));
        expect("DELETE /todos/1", 204, call("DELETE", "/todos/1", true, null));
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
