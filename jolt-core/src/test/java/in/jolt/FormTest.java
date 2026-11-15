package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FormTest {

    private static Request req(String contentType, String body) {
        Map<String, String> h =
                contentType == null ? Map.of() : Map.of("Content-Type", contentType);
        return new Request(
                "POST",
                "/f",
                "",
                h,
                body.getBytes(StandardCharsets.UTF_8),
                JoltConfig.defaults().jsonMapper());
    }

    private static final String FORM = "application/x-www-form-urlencoded";

    @Test
    void decodesPercentPlusAndUtf8() {
        Request r = req(FORM, "name=Ada+Lovelace&city=Z%C3%BCrich&empty=&flag");
        assertEquals("Ada Lovelace", r.form().get("name"));
        assertEquals("Zürich", r.form().get("city"));
        assertEquals("", r.form().get("empty"));
        assertEquals("", r.form().get("flag"));
    }

    @Test
    void decodesKeysAndKeepsFirstDuplicate() {
        Request r = req(FORM + "; charset=UTF-8", "a%20b=1&a%20b=2");
        assertEquals(Map.of("a b", "1"), r.form());
    }

    @Test
    void contentTypeMatchIsCaseInsensitive() {
        assertEquals(Map.of("a", "1"), req("Application/X-WWW-Form-Urlencoded", "a=1").form());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/plain"})
    void otherContentTypesGiveAnEmptyMap(String type) {
        assertEquals(Map.of(), req(type, "a=1").form());
    }

    @Test
    void missingContentTypeGivesAnEmptyMap() {
        assertEquals(Map.of(), req(null, "a=1").form());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a=%zz", "a=%4", "a=%"})
    void malformedEncodingIs400(String body) {
        HttpException e = assertThrows(HttpException.class, () -> req(FORM, body).form());
        assertEquals(400, e.status());
    }

    @Test
    void worksThroughTheTestClient() {
        App app = Jolt.create();
        app.post("/f", (req, res) -> res.text(req.form().get("x")));
        JoltTest.of(app)
                .post("/f")
                .header("Content-Type", FORM)
                .body("x=a+b".getBytes(StandardCharsets.UTF_8))
                .expect(200)
                .body("a b");
    }
}
