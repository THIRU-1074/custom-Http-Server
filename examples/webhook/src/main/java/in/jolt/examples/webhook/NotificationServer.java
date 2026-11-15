package in.jolt.examples.webhook;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

final class NotificationServer {

    record Registration(String userName, String url) {}

    private static final System.Logger LOG = System.getLogger("webhook");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

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
        List<String> urls = urls();
        for (String url : urls) {
            send(url, "{\"companyName\":\"" + name + "\"}");
        }
        return urls.size();
    }

    private static void send(String url, String json) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
        try {
            HttpResponse<Void> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            LOG.log(
                    System.Logger.Level.INFO,
                    "Webhook {0} answered {1}",
                    url,
                    response.statusCode());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Webhook " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static App create(BiPredicate<String, String> verifier) {
        App app = Jolt.create();
        app.use("/register", BasicAuth.require(verifier));
        app.post(
                "/register",
                (req, res) -> {
                    Registration r = req.body(Registration.class);
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
