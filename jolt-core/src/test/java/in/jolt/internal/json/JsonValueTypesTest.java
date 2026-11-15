package in.jolt.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.test.JoltTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonValueTypesTest {

    enum Color {
        RED,
        GREEN
    }

    record Event(
            Instant at,
            LocalDate day,
            LocalDateTime local,
            UUID id,
            BigDecimal amount,
            Color color,
            Optional<String> note) {}

    record Maybe(Optional<Integer> n, Optional<LocalDate> d, Optional<List<Color>> colors) {}

    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private static Event sample(Optional<String> note) {
        return new Event(
                Instant.parse("2024-05-06T07:08:09.123Z"),
                LocalDate.of(2024, 5, 6),
                LocalDateTime.of(2024, 5, 6, 7, 8, 9),
                ID,
                new BigDecimal("19.99"),
                Color.GREEN,
                note);
    }

    private static <T> T read(String json, Class<T> type) {
        return Json.bind(Json.parse(json), type);
    }

    @Test
    void everyTypeSurvivesARoundTrip() {
        Event e = sample(Optional.of("hi"));
        assertEquals(e, read(Json.write(e), Event.class));
    }

    @Test
    void valuesAreWrittenAsIsoStringsAndPlainNumbers() {
        String json = Json.write(sample(Optional.of("hi")));
        assertEquals(
                "{\"at\":\"2024-05-06T07:08:09.123Z\",\"day\":\"2024-05-06\","
                        + "\"local\":\"2024-05-06T07:08:09\","
                        + "\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"amount\":19.99,"
                        + "\"color\":\"GREEN\",\"note\":\"hi\"}",
                json);
    }

    @Test
    void emptyOptionalFieldIsOmittedOnWrite() {
        String json = Json.write(sample(Optional.empty()));
        assertEquals(false, json.contains("note"));
        assertEquals(Optional.empty(), read(json, Event.class).note());
    }

    @Test
    void nullAndAbsentReadAsEmptyOptional() {
        assertEquals(
                new Maybe(Optional.empty(), Optional.empty(), Optional.empty()),
                read("{}", Maybe.class));
        assertEquals(
                Optional.empty(), read("{\"n\":null,\"d\":null,\"colors\":null}", Maybe.class).n());
    }

    @Test
    void optionalWrapsItsElementType() {
        Maybe m =
                read("{\"n\":3,\"d\":\"2020-02-29\",\"colors\":[\"RED\",\"GREEN\"]}", Maybe.class);
        assertEquals(
                new Maybe(
                        Optional.of(3),
                        Optional.of(LocalDate.of(2020, 2, 29)),
                        Optional.of(List.of(Color.RED, Color.GREEN))),
                m);
        assertEquals(m, read(Json.write(m), Maybe.class));
    }

    @Test
    void optionalInsideMapsAndListsWritesNullWhenEmpty() {
        assertEquals("[1,null]", Json.write(List.of(Optional.of(1), Optional.empty())));
    }

    @Test
    void bigDecimalKeepsPrecisionBeyondDouble() {
        record Money(BigDecimal v) {}
        String digits = "12345678901234567890.123456789";
        assertEquals(new BigDecimal(digits), read("{\"v\":" + digits + "}", Money.class).v());
        assertEquals(new BigDecimal("1000.0"), read("{\"v\":1e3}", Money.class).v());
        assertEquals(0, new BigDecimal("-0.10").compareTo(read("{\"v\":-0.10}", Money.class).v()));
    }

    @Test
    void parsedLongDecimalsStillBindToDouble() {
        record D(double v) {}
        assertEquals(0.1234567890123456789, read("{\"v\":0.1234567890123456789}", D.class).v());
    }

    @Test
    void enumsMatchByNameCaseSensitively() {
        record C(Color c) {}
        assertEquals(Color.RED, read("{\"c\":\"RED\"}", C.class).c());
        assertThrows(IllegalArgumentException.class, () -> read("{\"c\":\"red\"}", C.class));
        assertThrows(IllegalArgumentException.class, () -> read("{\"c\":\"BLUE\"}", C.class));
    }

    @Test
    void invalidValuesAreIllegalArgumentNeverOtherExceptions() {
        for (String bad :
                new String[] {
                    "{\"at\":\"yesterday\"}",
                    "{\"at\":5}",
                    "{\"at\":\"2024-05-06\"}",
                    "{\"day\":\"2024-02-30\"}",
                    "{\"day\":\"06/05/2024\"}",
                    "{\"day\":{}}",
                    "{\"local\":\"2024-05-06\"}",
                    "{\"id\":\"1-1-1-1-1\"}",
                    "{\"id\":\"not-a-uuid\"}",
                    "{\"id\":7}",
                    "{\"amount\":\"abc\"}",
                    "{\"amount\":true}",
                    "{\"color\":7}",
                    "{\"note\":{}}"
                }) {
            assertThrows(IllegalArgumentException.class, () -> read(bad, Event.class), bad);
        }
    }

    @Test
    void depthLimitStillApplies() {
        String deep = "[".repeat(65) + "]".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    }

    @Test
    void invalidValueOverHttpGives400() {
        App app = Jolt.create();
        app.post("/e", (req, res) -> res.json(req.body(Event.class)));
        JoltTest.of(app).post("/e").json(Map.of("at", "nope")).expect(400).bodyContains("Event.at");
        var ok = JoltTest.of(app).post("/e").json(sample(Optional.empty())).expect(200);
        assertEquals(sample(Optional.empty()), ok.json(Event.class));
    }
}
