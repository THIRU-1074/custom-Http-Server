package in.jolt.examples.webhook;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
import in.jolt.Middleware;
import in.jolt.auth.BasicAuth;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

final class NotificationServer {

    record Registration(String userName, String url) {}

    private static final System.Logger LOG = System.getLogger("webhook");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private NotificationServer() {}

    static void register(String userName, String url) throws SQLException {
        try (Connection conn = DBConnection.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement(
                                "INSERT INTO webhookURLs (userName, url) VALUES (?, ?)")) {
            stmt.setString(1, userName);
            stmt.setString(2, url);
            stmt.executeUpdate();
        }
    }

    static List<String> urls() throws SQLException {
        List<String> urls = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT url FROM webhookURLs");
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                urls.add(rs.getString("url"));
            }
        }
        return urls;
    }

    static String companyFor(Map<?, ?> info) {
        if (info.containsKey("phNo")) {
            return "A";
        } else if (info.containsKey("email")) {
            return "B";
        } else if (info.containsKey("id")) {
            return "C";
        }
        return null;
    }

    static int broadcast(Map<?, ?> info) throws SQLException {
        String name = companyFor(info);
        if (name == null) {
            throw new HttpException(400, "Expected one of phNo, email or id");
        }
        int delivered = 0;
        for (String url : urls()) {
            if (send(url, "{\"companyName\":\"" + name + "\"}")) {
                delivered++;
            }
        }
        return delivered;
    }

    /** Accepts only absolute http or https URLs with a host. */
    static URI validateUrl(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the 400 below
        }
        throw new HttpException(400, "url must be an absolute http or https URL");
    }

    private static boolean send(String url, String json) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(validateUrl(url))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();
            HttpResponse<Void> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            LOG.log(
                    System.Logger.Level.INFO,
                    "Webhook {0} answered {1}",
                    url,
                    response.statusCode());
            return response.statusCode() / 100 == 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Webhook " + url + " failed", e);
        }
        return false;
    }

    static App create(BiPredicate<String, String> verifier) {
        App app = Jolt.create();
        Middleware auth = BasicAuth.require(verifier);
        app.use("/register", auth);
        app.use("/company", auth);
        app.post(
                "/register",
                (req, res) -> {
                    Registration r = req.body(Registration.class);
                    validateUrl(r.url());
                    register(r.userName(), r.url());
                    res.status(201).json(Map.of("registered", r.url()));
                });
        app.post(
                "/company",
                (req, res) -> res.json(Map.of("notified", broadcast(req.body(Map.class)))));
        app.get("/sayHello", (req, res) -> res.text("Hello World"));
        return app;
    }
}
