package in.jolt.examples.attendance;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.Response;
import in.jolt.Router;
import in.jolt.auth.JwtAuth;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

final class AttendanceApp {

    record Login(String user_name, String password) {}

    private AttendanceApp() {}

    static boolean authenticate(String userId, String password) {
        String sql = "SELECT 1 FROM credentials WHERE user_id = ? AND password = ?";
        try (Connection conn = DBConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, password);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Credential lookup failed", e);
        }
    }

    static App create(JwtAuth jwt) {
        return create(jwt, JoltConfig.defaults());
    }

    static App create(JwtAuth jwt, JoltConfig config) {
        App app = Jolt.create(config);

        app.get("/", (req, res) -> res.html(resourceText("index.html")));
        app.get("/favicon.ico", (req, res) -> image(res, "image.png"));
        app.get(
                "/getClassesInfo",
                (req, res) ->
                        res.header("X-Content-Type-Options", "nosniff")
                                .json(Map.of("Name", "RegNo", "Thiru", "22BEC1473")));
        app.get(
                "/paramTest/:param1/:param2",
                (req, res) ->
                        res.json(
                                Map.of(
                                        "param1", req.param("param1"),
                                        "param2", req.param("param2"),
                                        "query", req.query())));
        app.post("/imageTest", (req, res) -> res.json(Map.of("bytes", req.body().length)));

        Router auth = new Router();
        auth.get("/login", (req, res) -> res.html(resourceText("login.html")));
        auth.post(
                "/login",
                (req, res) -> {
                    Login login = req.body(Login.class);
                    if (login.user_name() != null
                            && login.password() != null
                            && authenticate(login.user_name(), login.password())) {
                        String token =
                                jwt.sign(
                                        login.user_name(),
                                        Map.of("role", "student"),
                                        Duration.ofHours(1));
                        res.json(Map.of("JWT", token, "message", "Login Successful!"));
                    } else {
                        res.status(401)
                                .json(Map.of("message", "UserName or Password incorrect..."));
                    }
                });
        app.use("/auth", auth);

        app.use("/me", jwt.middleware());
        app.get("/me", (req, res) -> res.json(Map.of("user", req.attr("user"))));
        return app;
    }

    private static void image(Response res, String name) {
        res.header("Content-Type", "image/png").send(resourceBytes(name));
    }

    private static String resourceText(String name) {
        return new String(resourceBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] resourceBytes(String name) {
        try (InputStream in = AttendanceApp.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
