package in.jolt.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CodecTest {

    @Test
    void percentEncodeKeepsOnlyUnreservedCharacters() {
        String[][] cases = {
            {"", "", ""},
            {"aZ09-._~", "aZ09-._~", "aZ09-._~"},
            {"a b", "a%20b", "a%20b"},
            {"/x/y", "%2Fx%2Fy", "/x/y"},
            {"a&b=c+d%", "a%26b%3Dc%2Bd%25", "a%26b%3Dc%2Bd%25"},
            {"é", "%C3%A9", "%C3%A9"},
            {"😀", "%F0%9F%98%80", "%F0%9F%98%80"},
            {"<>\"\r\n", "%3C%3E%22%0D%0A", "%3C%3E%22%0D%0A"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Codec.percentEncode(c[0], false), c[0]);
            assertEquals(c[2], Codec.percentEncode(c[0], true), c[0]);
            assertEquals(c[0], Codec.percentDecode(c[1], false), c[0]);
        }
    }
}
