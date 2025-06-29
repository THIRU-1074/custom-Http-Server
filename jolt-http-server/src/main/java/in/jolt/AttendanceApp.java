package in.jolt;

import java.sql.*;

public class AttendanceApp {
    public static boolean authenticate(String userId, String password) {
        String sql = "SELECT 1 FROM credentials WHERE user_id = ? AND password = ?";
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, password);

                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next(); // true if found
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            DBConnection.closeConnection(conn);
        }
    }

    public static void start(int port) {
        Router router = new Router();
        Jolt.GET("/", (req, res) -> {
            try {
                Jolt.authorizeClient(req, res, "Site-Access");
                res.setBody(new html("src/main/resources/index.html"));
            } catch (UnauthorizedException e) {
                e.printStackTrace();
            }
        });
        Jolt.GET("/favicon.ico", (req, res) -> {
            res.setBody(new image("src/main/resources/image.png"));
        });
        Jolt.GET("/getClassesInfo", (req, res) -> {
            res.headers.put("Access-Control-Allow-Origin", "*");
            res.headers.put("X-Content-Type-Options", "nosniff");
            JSON localObJson = new JSON();
            localObJson.add("Name", "RegNo");
            localObJson.add("Thiru", "22BEC1473");
            res.body = localObJson;
        });
        router.POST("/login", (req, res) -> {
            JSON localObJson = (JSON) req.getBody();
            String userName = (String) localObJson.get("user_name");
            String password = (String) localObJson.get("password");
            JSON msg = new JSON();
            if (authenticate(userName, password)) {
                msg.add("JWT", Auth.createJWT(Auth.createJWTPayLoad(userName, "student", "19/6/2025", "21:30")));
                msg.add("message", "Login Successful!");
            } else {
                msg.add("message", "UserName or Password incorrect...");
            }
            res.setBody(msg);
        });
        router.GET("/login", (req, res) -> {
            res.headers.put("Access-Control-Allow-Origin", "*");
            res.headers.put("X-Content-Type-Options", "nosniff");
            res.setBody(new html("src/main/resources/login.html"));
            // System.out.println("Body setted");
        });
        Jolt.use("/auth", router);
        Jolt.listen(port, () -> {
            System.out.println("The Server is Running at port " + port + "...");
        });
    }
}
