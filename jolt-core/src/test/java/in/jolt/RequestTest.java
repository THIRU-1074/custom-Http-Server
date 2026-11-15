package in.jolt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestTest {

    private static Request req(String query, Map<String, String> headers, String body) {
        return new Request(
                "POST",
                "/p",
                query,
                headers,
                body.getBytes(StandardCharsets.UTF_8),
                JoltConfig.defaults().jsonMapper());
    }

    @Test
    void headersAreCaseInsensitive() {
        Request r = req("", Map.of("Content-Type", "x"), "");
        assertEquals("x", r.header("content-type"));
        assertEquals("x", r.headers().get("CONTENT-TYPE"));
        assertEquals("POST", r.method());
        assertEquals("/p", r.path());
    }

    @Test
    void queryIsDecodedFirstValueWinsAndToleratesJunk() {
        Request r = req("a=1&a=2&b=x%20y&c&d=%zz&=&e=1+2", Map.of(), "");
        assertEquals("1", r.query("a"));
        assertEquals("x y", r.query("b"));
        assertEquals("", r.query("c"));
        assertEquals("%zz", r.query("d"));
        assertEquals("1 2", r.query("e"));
        assertNull(r.query("nope"));
        assertEquals(List.of("a", "b", "c", "d", "", "e"), List.copyOf(r.query().keySet()));
    }

    @Test
    void nullQueryIsEmpty() {
        assertEquals(Map.of(), new Request("GET", "/", null, Map.of(), null, null).query());
    }

    @Test
    void cookiesComeFromTheCookieHeader() {
        Request r = req("", Map.of("Cookie", "a=1; b=two;  c=%20x ;;"), "");
        assertEquals("1", r.cookie("a"));
        assertEquals("two", r.cookie("b"));
        assertEquals(" x", r.cookie("c"));
        assertNull(r.cookie("d"));
        assertNull(req("", Map.of("Cookies", "a=1"), "").cookie("a"));
    }

    @Test
    void bodyAccessors() {
        Request r = req("", Map.of(), "{\"a\":1}");
        assertEquals("{\"a\":1}", r.text());
        assertEquals("{\"a\":1}", r.body(String.class));
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), r.body(byte[].class));
        assertEquals(Map.of("a", 1L), r.body(Map.class));
        assertThrows(HttpException.class, () -> req("", Map.of(), "").body(Map.class));
    }

    @Test
    void attributesCarryDataBetweenMiddlewareAndHandlers() {
        Request r = req("", Map.of(), "");
        assertEquals("bob", r.attr("user", "bob").attr("user"));
        assertNull(r.attr("other"));
        assertNull(r.param("id"));
    }
}
