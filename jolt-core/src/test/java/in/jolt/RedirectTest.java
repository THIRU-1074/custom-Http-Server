package in.jolt;

import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedirectTest {

    @Test
    void defaultsTo302WithLocation() {
        App app = Jolt.create();
        app.get("/old", (req, res) -> res.redirect("/new"));
        JoltTest.of(app).get("/old").expect(302).header("Location", "/new").body("");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void allowsRedirectStatuses(int code) {
        App app = Jolt.create();
        app.get("/old", (req, res) -> res.redirect(code, "/new"));
        JoltTest.of(app).get("/old").expect(code).header("Location", "/new");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 304, 300, 404, 0})
    void rejectsOtherStatuses(int code) {
        Response res = new Response(JoltConfig.defaults().jsonMapper());
        assertThrows(IllegalArgumentException.class, () -> res.redirect(code, "/x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a\r\nSet-Cookie: x=1", "/a\nb", "/a\rb"})
    void rejectsLineBreaksInUrl(String url) {
        Response res = new Response(JoltConfig.defaults().jsonMapper());
        assertThrows(IllegalArgumentException.class, () -> res.redirect(url));
    }
}
