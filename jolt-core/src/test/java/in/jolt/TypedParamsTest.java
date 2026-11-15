package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedParamsTest {

    enum Color {
        RED,
        BLUE
    }

    private static Request req(String query) {
        return new Request("GET", "/", query, java.util.Map.of(), null, null);
    }

    @Test
    void queryConvertsEverySupportedType() {
        UUID id = UUID.randomUUID();
        Request r =
                req(
                        "l=9000000000&i=7&b=TRUE&d=2.5&u="
                                + id
                                + "&c=blue&t=2024-05-01T10:00:00Z&day=2024-05-01&s=x");
        assertEquals(9000000000L, r.query("l", Long.class, null));
        assertEquals(7, r.query("i", Integer.class, null));
        assertEquals(7, r.query("i", int.class, 0));
        assertEquals(true, r.query("b", Boolean.class, null));
        assertEquals(2.5, r.query("d", Double.class, null));
        assertEquals(id, r.query("u", UUID.class, null));
        assertEquals(Color.BLUE, r.query("c", Color.class, null));
        assertEquals(Instant.parse("2024-05-01T10:00:00Z"), r.query("t", Instant.class, null));
        assertEquals(LocalDate.of(2024, 5, 1), r.query("day", LocalDate.class, null));
        assertEquals("x", r.query("s", String.class, null));
    }

    @Test
    void defaultAppliesOnlyWhenAbsent() {
        Request r = req("page=3");
        assertEquals(1, r.query("size", int.class, 1));
        assertEquals(3, r.query("page", int.class, 1));
        assertNull(r.query("none", Long.class, null));
    }

    @Test
    void paramConvertsPathValues() {
        App app = Jolt.create();
        app.get("/u/:id", p -> p.param("id", Long.class) + 1);
        app.get("/c/:c", p -> p.param("c", Color.class).name());
        JoltTest t = JoltTest.of(app);
        t.get("/u/41").expect(200).body("42");
        t.get("/c/red").expect(200).body("\"RED\"");
    }

    @Test
    void malformedValueIs400NamingTheParameter() {
        App app = Jolt.create();
        app.get("/u/:id", p -> p.param("id", Long.class));
        app.get("/q", p -> p.query("page", int.class, 1));
        JoltTest t = JoltTest.of(app);
        t.get("/u/abc").expect(400).body("Invalid path parameter 'id': expected Long");
        t.get("/q?page=x").expect(400).body("Invalid query parameter 'page': expected int");
        t.get("/q?page=99999999999").expect(400);
        t.get("/q").expect(200).body("1");
    }

    @Test
    void invalidValuesPerType() {
        for (String q :
                new String[] {
                    "b=yes",
                    "d=NaN",
                    "d=Infinity",
                    "u=nope",
                    "c=green",
                    "t=yesterday",
                    "day=2024-13-40"
                }) {
            Request r = req(q);
            String name = q.substring(0, q.indexOf('='));
            Class<?> type =
                    switch (name) {
                        case "b" -> Boolean.class;
                        case "d" -> Double.class;
                        case "u" -> UUID.class;
                        case "c" -> Color.class;
                        case "t" -> Instant.class;
                        default -> LocalDate.class;
                    };
            HttpException e = assertThrows(HttpException.class, () -> r.query(name, type, null));
            assertEquals(400, e.status(), q);
        }
    }

    @Test
    void unsupportedTypeIsAProgrammingError() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> req("a=1").query("a", java.math.BigDecimal.class, null));
    }

    @Test
    void absentPathParamIsNull() {
        assertNull(req("").param("id", Long.class));
    }
}
