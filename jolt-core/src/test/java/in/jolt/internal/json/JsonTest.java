package in.jolt.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    enum Role {
        ADMIN,
        USER
    }

    record Address(String city) {}

    record User(
            String name,
            int age,
            Integer score,
            boolean active,
            double ratio,
            Role role,
            List<String> tags,
            List<Address> addresses,
            Map<String, Integer> counts,
            Address home,
            BigDecimal balance,
            Object extra) {}

    @Test
    void parsesAllValueTypes() {
        Object v =
                Json.parse(
                        " {\"a\":1,\"b\":-2.5e1,\"c\":[true,false,null],\"d\":\"x\\ny\\u0041\\\"\","
                                + "\"e\":{}, \"f\":[]} ");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(1L, m.get("a"));
        assertEquals(-25.0, m.get("b"));
        assertEquals(java.util.Arrays.asList(true, false, null), m.get("c"));
        assertEquals("x\nyA\"", m.get("d"));
        assertEquals(Map.of(), m.get("e"));
        assertEquals(List.of(), m.get("f"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{",
                "{\"a\"}",
                "{\"a\":1,}",
                "[1,]",
                "[1 2]",
                "{a:1}",
                "\"unterminated",
                "\"bad\\q\"",
                "\"bad\\u00G0\"",
                "\"bad\\u00\"",
                "tru",
                "1.2.3",
                "{} extra",
                "\"tab\there\"",
                "\"trailing\\"
            })
    void rejectsInvalidJson(String bad) {
        var e = assertThrows(IllegalArgumentException.class, () -> Json.parse(bad));
        assertTrue(e.getMessage().startsWith("Body is not valid JSON at char "), e.getMessage());
    }

    @Test
    void reportsCharPosition() {
        var e = assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": nope}"));
        assertEquals("Body is not valid JSON at char 6", e.getMessage());
    }

    @Test
    void writesValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "q\"\\\n\r\t\u0001");
        m.put("n", null);
        m.put("l", List.of(1, 2.5, true));
        m.put("arr", new int[] {1, 2});
        m.put("e", Role.ADMIN);
        m.put("nan", Double.NaN);
        m.put("fnan", Float.POSITIVE_INFINITY);
        m.put("c", 'x');
        m.put("r", new Address("Chennai"));
        assertEquals(
                "{\"s\":\"q\\\"\\\\\\n\\r\\t\\u0001\",\"n\":null,\"l\":[1,2.5,true],\"arr\":[1,2],"
                        + "\"e\":\"ADMIN\",\"nan\":null,\"fnan\":null,\"c\":\"x\","
                        + "\"r\":{\"city\":\"Chennai\"}}",
                Json.write(m));
    }

    @Test
    void refusesToWriteUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
    }

    @Test
    void bindsRecords() {
        String json =
                "{\"name\":\"Thiru\",\"age\":21,\"score\":7,\"active\":true,\"ratio\":0.5,"
                        + "\"role\":\"ADMIN\",\"tags\":[\"a\",\"b\"],\"addresses\":[{\"city\":\"X\"}],"
                        + "\"counts\":{\"k\":3},\"home\":{\"city\":\"Y\"},\"balance\":12.5,"
                        + "\"extra\":{\"z\":1}}";
        User u = new BuiltInJsonMapper().read(json.getBytes(StandardCharsets.UTF_8), User.class);
        assertEquals("Thiru", u.name());
        assertEquals(21, u.age());
        assertEquals(7, u.score());
        assertTrue(u.active());
        assertEquals(0.5, u.ratio());
        assertEquals(Role.ADMIN, u.role());
        assertEquals(List.of("a", "b"), u.tags());
        assertEquals(List.of(new Address("X")), u.addresses());
        assertEquals(Map.of("k", 3), u.counts());
        assertEquals("Y", u.home().city());
        assertEquals(new BigDecimal("12.5"), u.balance());
        assertEquals(Map.of("z", 1L), u.extra());
    }

    @Test
    void missingFieldsBecomeNullOrPrimitiveDefaults() {
        User u = Json.bind(Json.parse("{}"), User.class);
        assertNull(u.name());
        assertEquals(0, u.age());
        assertNull(u.score());
    }

    @Test
    void bindsToMapAndList() {
        assertEquals(Map.of("a", 1L), Json.bind(Json.parse("{\"a\":1}"), Map.class));
        assertEquals(List.of(1L), Json.bind(Json.parse("[1]"), List.class));
        assertEquals("5", Json.bind(Json.parse("5"), String.class));
        assertEquals(5L, Json.bind(Json.parse("5"), Long.class));
        assertEquals((short) 5, Json.bind(Json.parse("5"), Short.class));
        assertEquals((byte) 5, Json.bind(Json.parse("5"), Byte.class));
        assertEquals(5.0f, Json.bind(Json.parse("5"), Float.class));
    }

    @Test
    void typeMismatchesExplainTheField() {
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Json.bind(Json.parse("{\"age\":\"old\"}"), User.class));
        assertEquals("Expected a number for User.age", e.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"active\":1}"), User.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"role\":\"NOPE\"}"), User.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"name\":{}}"), User.class));
        assertThrows(
                IllegalArgumentException.class, () -> Json.bind(Json.parse("[1]"), User.class));
    }
}
