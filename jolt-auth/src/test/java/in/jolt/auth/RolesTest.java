package in.jolt.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RolesTest {

    private static final JwtAuth JWT = JwtAuth.hs256("test-secret-test-secret-test-secret");

    private static JoltTest client() {
        App app = Jolt.create();
        app.use("/admin", JWT.middleware());
        app.use("/admin", Auth.requireRole("admin", "root"));
        app.get("/admin/x", (req, res) -> res.text("in"));
        app.use("/both", JWT.middleware());
        app.use("/both", Auth.requireAll("editor", "billing"));
        app.get("/both/x", (req, res) -> res.text("in"));
        app.use("/basic", BasicAuth.require((u, p) -> true));
        app.use("/basic", Auth.requireRole("admin"));
        app.get("/basic/x", (req, res) -> res.text("in"));
        app.use("/open", Auth.requireRole("admin"));
        app.get("/open/x", (req, res) -> res.text("in"));
        return JoltTest.of(app);
    }

    private static String token(Map<String, ?> claims) {
        return "Bearer " + JWT.sign("u", claims, Duration.ofMinutes(5));
    }

    @Test
    void missingTokenIs401() {
        client().get("/admin/x").expect(401).header("WWW-Authenticate", "Bearer");
    }

    @Test
    void unauthenticatedRequestWithoutAuthMiddlewareIs401() {
        client().get("/open/x").expect(401).header("WWW-Authenticate", "Bearer");
    }

    @Test
    void wrongRoleIs403() {
        client().get("/admin/x").header("Authorization", token(Map.of("role", "user"))).expect(403);
    }

    @Test
    void noRolesClaimIs403() {
        client().get("/admin/x").header("Authorization", token(Map.of())).expect(403);
    }

    @Test
    void stringRoleClaimPasses() {
        client().get("/admin/x")
                .header("Authorization", token(Map.of("role", "admin")))
                .expect(200)
                .body("in");
    }

    @Test
    void listRolesClaimPassesWithAnyOf() {
        client().get("/admin/x")
                .header("Authorization", token(Map.of("roles", List.of("user", "root"))))
                .expect(200);
    }

    @Test
    void requireAllNeedsEveryRole() {
        var t = client();
        t.get("/both/x")
                .header("Authorization", token(Map.of("roles", List.of("editor"))))
                .expect(403);
        t.get("/both/x")
                .header("Authorization", token(Map.of("roles", List.of("editor", "billing"))))
                .expect(200);
    }

    @Test
    void basicAuthUsersHaveNoRoles() {
        client().get("/basic/x").header("Authorization", "Basic dTpw").expect(403);
    }

    @Test
    void emptyRoleListIsRefused() {
        assertThrows(IllegalArgumentException.class, Auth::requireRole);
        assertThrows(IllegalArgumentException.class, Auth::requireAll);
    }
}
