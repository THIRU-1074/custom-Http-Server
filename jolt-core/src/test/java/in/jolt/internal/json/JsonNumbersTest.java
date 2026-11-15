package in.jolt.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class JsonNumbersTest {

    record Ints(int i, long l, short s, byte b) {}

    record Wide(BigInteger big, double d, Long boxed) {}

    private static Ints ints(String json) {
        return Json.bind(Json.parse(json), Ints.class);
    }

    @Test
    void acceptsValuesInRange() {
        assertEquals(
                new Ints(-2147483648, Long.MAX_VALUE, (short) -32768, (byte) 127),
                ints("{\"i\":-2147483648,\"l\":9223372036854775807,\"s\":-32768,\"b\":127}"));
        assertEquals(
                new Ints(1, 2, (short) 3, (byte) 4), ints("{\"i\":1.0,\"l\":2e0,\"s\":3,\"b\":4}"));
    }

    @Test
    void rejectsOutOfRangeAndFractionalValues() {
        for (String bad :
                new String[] {
                    "{\"i\":4294967297}",
                    "{\"i\":2147483648}",
                    "{\"i\":1.9}",
                    "{\"s\":40000}",
                    "{\"b\":300}",
                    "{\"l\":9223372036854775808}",
                    "{\"l\":1e30}",
                    "{\"l\":0.5}"
                }) {
            var e = assertThrows(IllegalArgumentException.class, () -> ints(bad), bad);
            assertEquals(true, e.getMessage().startsWith("Expected an integer in range"), bad);
        }
    }

    @Test
    void integersBeyondLongParseInsteadOfReportingInvalidJson() {
        Object v = Json.parse("123456789012345678901234567890");
        assertEquals(new BigInteger("123456789012345678901234567890"), v);
        Wide w =
                Json.bind(
                        Json.parse(
                                "{\"big\":123456789012345678901234567890,\"d\":1e3,\"boxed\":7}"),
                        Wide.class);
        assertEquals(new BigInteger("123456789012345678901234567890"), w.big());
        assertEquals(1000.0, w.d());
        assertEquals(7L, w.boxed());
        assertEquals(1.2345678901234568E29, Json.bind(v, Double.class));
        assertEquals("123456789012345678901234567890", Json.write(v));
    }
}
