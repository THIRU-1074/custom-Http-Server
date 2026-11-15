package in.jolt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseValidationTest {

    private static Response res() {
        return new Response(JoltConfig.defaults().jsonMapper());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a,b", "a;b", "a=b", "a(b", "a[b", "a\"b", "a\b", "é", ""})
    void rejectsCookieNamesOutsideTheTokenGrammar(String name) {
        assertThrows(IllegalArgumentException.class, () -> res().cookie(name, "v"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"a,b", "a;b", "a b", " a", "a\"b", "a\b", "a\tb", "a\u0001b", "é", "\"a"})
    void rejectsCookieValuesOutsideCookieOctet(String value) {
        assertThrows(IllegalArgumentException.class, () -> res().cookie("sid", value));
    }

    @Test
    void acceptsTokenNamesAndCookieOctetValues() {
        Response r = res().cookie("__Host-sid_1.x", "abc123!#$%&'()*+-./:<=>?@[]^_`{|}~");
        assertEquals(1, r.headers().size());
        assertDoesNotThrow(() -> res().cookie("a", ""));
        assertDoesNotThrow(() -> res().cookie("a", "\"quoted\""));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Path=/; Evil=1",
                "Path=/; HttpOnly; SameSite=Weird",
                "Path=/;; Secure",
                "Path=/ x; Secure",
                "Max-Age=abc",
                "Domain=exa mple.com",
                "Secure=1",
                "HttpOnly; Foo",
                "Path=/\u0001",
                "SameSite",
            })
    void rejectsAttributesOutsideTheAllowlist(String attrs) {
        assertThrows(IllegalArgumentException.class, () -> res().cookie("a", "b", attrs));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Path=/; HttpOnly; SameSite=Lax",
                "path=/a/b; domain=.example.com; max-age=-1; secure; httponly; samesite=none",
                "Expires=Wed, 21 Oct 2015 07:28:00 GMT",
                "",
            })
    void acceptsAllowlistedAttributes(String attrs) {
        assertDoesNotThrow(() -> res().cookie("a", "b", attrs));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X(Y", "X Y", "X:Y", "X\"Y", "X/Y", "X,Y", "X;Y", "X[Y", "X{Y", "é"})
    void rejectsHeaderNamesOutsideTheTokenGrammar(String name) {
        assertThrows(IllegalArgumentException.class, () -> res().header(name, "v"));
    }

    @Test
    void acceptsTokenHeaderNames() {
        assertDoesNotThrow(() -> res().header("X-Request_Id.1~", "v"));
    }
}
