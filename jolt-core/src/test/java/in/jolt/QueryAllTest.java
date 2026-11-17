package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryAllTest {

    private static Request req(String query) {
        return new Request("GET", "/", query, Map.of(), new byte[0], null);
    }

    @Test
    void returnsEveryValueInOrder() {
        Request r = req("genre=drama&city=pune&genre=comedy&genre=");
        assertEquals(List.of("drama", "comedy", ""), r.queryAll("genre"));
        assertEquals(List.of("pune"), r.queryAll("city"));
        assertEquals("drama", r.query("genre"));
    }

    @Test
    void absentNameGivesAnEmptyList() {
        assertTrue(req("a=1").queryAll("b").isEmpty());
        assertTrue(req("").queryAll("b").isEmpty());
    }

    @Test
    void decodesLikeQuery() {
        Request r = req("q=a+b&q=x%20y&q=%zz&q&%67=z");
        assertEquals(List.of("a b", "x y", "%zz", ""), r.queryAll("q"));
        assertEquals(List.of("z"), r.queryAll("g"));
    }

    @Test
    void resultIsReadOnly() {
        assertThrows(UnsupportedOperationException.class, () -> req("a=1").queryAll("a").add("x"));
    }

    @Test
    void parsesOnce() {
        Request r = req("a=1&a=2");
        assertSame(r.queryAll("a"), r.queryAll("a"));
    }

    @Test
    void typedValuesUseTheSameConversionsAsQuery() {
        Request r = req("id=1&id=22&id=9000000000&on=true&on=FALSE");
        assertEquals(List.of(1L, 22L, 9000000000L), r.queryAll("id", Long.class));
        assertEquals(List.of(true, false), r.queryAll("on", boolean.class));
        assertTrue(r.queryAll("none", Long.class).isEmpty());
    }

    @Test
    void typedBadValueIsA400NamingTheParameter() {
        HttpException e =
                assertThrows(
                        HttpException.class, () -> req("id=1&id=x").queryAll("id", Long.class));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("'id'"), e.getMessage());
    }

    @Test
    void unsupportedTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> req("a=1").queryAll("a", Object.class));
    }

    @Test
    void typedHandlersSeeItOnParams() {
        App app = Jolt.create();
        app.get(
                "/shows",
                p -> p.queryAll("genre", String.class).size() + ":" + p.queryAll("genre"));
        JoltTest t = JoltTest.of(app);
        t.get("/shows?genre=drama&city=pune&genre=comedy")
                .expect(200)
                .body("\"2:[drama, comedy]\"");
        t.get("/shows").expect(200).body("\"0:[]\"");
    }

    @Test
    void joltTestSendsRepeatedParameters() {
        App app = Jolt.create();
        app.get("/s", (req, res) -> res.text(String.join(",", req.queryAll("g"))));
        t(app).get("/s?g=a&g=b%2Cc&g=d+e").expect(200).body("a,b,c,d e");
        t(app).get("/s?g=1&g=%zz").expect(200).body("1,%zz");
    }

    private static JoltTest t(App app) {
        return JoltTest.of(app);
    }
}
