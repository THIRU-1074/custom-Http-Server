package in.jolt;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;

public class NotificationServer {
    public static void regWebHookURL(String url, String userName) {
        String insertSQL = "INSERT INTO webhookURLs (userName, url) VALUES (?, ?)";
        Connection conn = null;

        try {
            // Get DB connection
            conn = DBConnection.getConnection(); // assumes this method is defined

            try (PreparedStatement stmt = conn.prepareStatement(insertSQL)) {
                // String userName = claim.getString("userId"); // extract userName

                stmt.setString(1, userName);
                stmt.setString(2, url);

                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    System.out.println("Webhook URL registered successfully for user: " + userName);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            DBConnection.closeConnection(conn); // assumes this method is defined
        }
    }

    public static void sendURL(String url, String content) {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(content))
                .build();
        HttpResponse<String> response = null;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Status code: " + response.statusCode());
        System.out.println("Response: " + response.body());
    }

    public static void broadCastToHooks(JSONObject companyInfo) {
        // company A-----> phno 1
        // '''''' B-----> email 3
        // '''''' C-----> id 2
        String name = null;
        if (companyInfo.has("phNo")) {
            name = "A";
        } else if (companyInfo.has("id")) {
            name = "C";
        } else if (companyInfo.has("email")) {
            name = "B";
        } else {

        }
        String sql = "SELECT url FROM webhookURLs";
        Connection conn = null;

        try {
            conn = DBConnection.getConnection(); // your DB connection method
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    String url = rs.getString("url");
                    System.out.println("Sending webhook to: " + url);

                    // Execute logic (e.g., send HTTP request)
                    sendURL(url, "{\"company Name\":\"" + name + "\"}");
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            DBConnection.closeConnection(conn);
        }

    }

    public static void start(int port) {
        Jolt.POST("/register", (req, res) -> {
            Jolt.authorizeClient(req, res, "web hook reg");
            JSON localObj = (JSON) req.body;
            regWebHookURL(localObj.get("url"), localObj.get("userName"));
        });
        Jolt.POST("/company", (req, res) -> {
            JSON localJson = (JSON) req.body;
            broadCastToHooks(localJson.json);
            // res.statusCode = 200;
        });
        Jolt.GET("/sayHello", (req, res) -> {
            res.statusCode = 200;
            System.out.println("Hello World");
        });
        Jolt.listen(port, () -> {
            System.out.println("The Server is Running at port " + port + "...");
        });
    }
}
