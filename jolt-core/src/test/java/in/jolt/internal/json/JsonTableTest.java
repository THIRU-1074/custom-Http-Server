package in.jolt.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTableTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "0|0",
                "-0|0",
                "123|123",
                "-45|-45",
                "1.5|1.5",
                "1e2|100.0",
                "1E+2|100.0",
                "2.5e-1|0.25",
                "true|true",
                "false|false",
                "null|null",
                "\"\"|\"\"",
                "\"a b\"|\"a b\"",
                "\"\\u00e9\"|\"\u00e9\"",
                "\"\\/\"|\"/\"",
                "[]|[]",
                "[1, 2 ,3]|[1,2,3]",
                "{}|{}",
                "{ \"a\" : [ { \"b\" : null } ] }|{\"a\":[{\"b\":null}]}",
                "[[[[1]]]]|[[[[1]]]]"
            })
    void parseThenWriteNormalises(String in, String out) {
        assertEquals(out, Json.write(Json.parse(in)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "01x",
                "--1",
                "1e",
                "[",
                "]",
                "{\"a\":}",
                "{\"a\":1 \"b\":2}",
                "[1,,2]",
                "nul",
                "True",
                "'single'",
                "\"\\u12\"",
                "{\"a\":1}}"
            })
    void rejectsMalformedInput(String bad) {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(bad));
    }
}
