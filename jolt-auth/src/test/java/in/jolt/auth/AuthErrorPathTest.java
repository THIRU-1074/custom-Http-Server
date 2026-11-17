package in.jolt.auth;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.Reply;
import in.jolt.RequestId;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthErrorPathTest {

    private static final String PROBLEM = "application/problem+json; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final JwtAuth JWT = JwtAuth.hs256("test-secret-test-secret-test-secret");

    private static App app(boolean problem) {
        App app = Jolt.create(JoltConfig.defaults().problemJson(problem));
        app.use("/jwt", JWT.middleware());
        app.use("/jwt", Auth.requireRole("ADMIN"));
        app.get("/jwt/x", (req, res) -> res.text("in"));
        app.use("/role", Auth.requireRole("ADMIN"));
        app.get("/role/x", (req, res) -> res.text("in"));
        app.use("/basic", BasicAuth.require((u, p) -> false));
        app.get("/basic/x", (req, res) -> res.text("in"));
        return app;
    }

    private static String bearer(String role) {
        return "Bearer " + JWT.sign("u", Map.of("role", role), Duration.ofMinutes(5));
    }

    @Test
    void rejectionsAreProblemJsonWhenEnabled() {
        JoltTest t = JoltTest.of(app(true));
        t.get("/jwt/x")
                .expect(401)
                .header("Content-Type", PROBLEM)
                .header("WWW-Authenticate", "Bearer")
                .jsonEquals("type", "about:blank")
                .jsonEquals("title", "Unauthorized")
                .jsonEquals("status", 401)
                .jsonEquals("detail", "Missing bearer token");
        t.get("/jwt/x")
                .header("Authorization", "Bearer garbage")
                .expect(401)
                .header("Content-Type", PROBLEM)
                .header("WWW-Authenticate", "Bearer")
                .jsonEquals("detail", "Invalid or expired token");
        t.get("/jwt/x")
                .header("Authorization", bearer("USER"))
                .expect(403)
                .header("Content-Type", PROBLEM)
                .jsonEquals("title", "Forbidden")
                .jsonEquals("status", 403);
        t.get("/role/x")
                .expect(401)
                .header("Content-Type", PROBLEM)
                .header("WWW-Authenticate", "Bearer");
        t.get("/basic/x")
                .expect(401)
                .header("Content-Type", PROBLEM)
                .header("WWW-Authenticate", "Basic realm=\"jolt\"");
        t.get("/jwt/x").header("Authorization", bearer("ADMIN")).expect(200).body("in");
    }

    @Test
    void rejectionsStayPlainTextWhenProblemJsonIsOff() {
        JoltTest t = JoltTest.of(app(false));
        t.get("/jwt/x")
                .expect(401)
                .header("Content-Type", TEXT)
                .header("WWW-Authenticate", "Bearer")
                .body("Missing bearer token");
        t.get("/jwt/x")
                .header("Authorization", "Bearer garbage")
                .expect(401)
                .body("Invalid or expired token");
        t.get("/jwt/x")
                .header("Authorization", bearer("USER"))
                .expect(403)
                .header("Content-Type", TEXT)
                .body("Forbidden");
        t.get("/role/x").expect(401).body("Authentication required");
        t.get("/basic/x").expect(401).body("Invalid credentials");
    }

    @Test
    void rejectionsFlowThroughOnError() {
        App app = app(false);
        app.onError(
                HttpException.class,
                e -> Reply.status(e.status(), Map.of("code", "AUTH_" + e.status())));
        JoltTest t = JoltTest.of(app);
        t.get("/jwt/x")
                .expect(401)
                .header("WWW-Authenticate", "Bearer")
                .jsonEquals("code", "AUTH_401");
        t.get("/jwt/x")
                .header("Authorization", bearer("USER"))
                .expect(403)
                .jsonEquals("code", "AUTH_403");
        t.get("/basic/x")
                .expect(401)
                .header("WWW-Authenticate", "Basic realm=\"jolt\"")
                .jsonEquals("code", "AUTH_401");
    }

    @Test
    void requestAwareMappersAndProblemBodiesSeeTheRequestId() {
        App app = Jolt.create(JoltConfig.defaults().problemJson(true));
        app.use(RequestId.middleware());
        app.use("/mapped", JWT.middleware());
        app.get("/mapped/x", (req, res) -> res.text("in"));
        app.onError(
                HttpException.class,
                (e, req) -> Reply.status(e.status(), Map.of("path", req.path())));
        JoltTest.of(app)
                .get("/mapped/x")
                .header("X-Request-Id", "r-1")
                .expect(401)
                .header("WWW-Authenticate", "Bearer")
                .jsonEquals("path", "/mapped/x")
                .jsonEquals("requestId", "r-1");
    }
}
