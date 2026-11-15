package in.jolt.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonDepthTest {

    @Test
    void parseRejectsDeepNestingInsteadOfOverflowingTheStack() {
        var e = assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100_000)));
        assertTrue(e.getMessage().contains("nested too deeply"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":".repeat(100_000)));
    }

    @Test
    void moderateNestingStillWorks() {
        Object ok = Json.parse("[".repeat(60) + "]".repeat(60));
        assertEquals(120, Json.write(ok).length());
    }

    @Test
    void writeRejectsDeepAndCyclicStructures() {
        List<Object> deep = new ArrayList<>();
        List<Object> cur = deep;
        for (int i = 0; i < 100; i++) {
            List<Object> next = new ArrayList<>();
            cur.add(next);
            cur = next;
        }
        assertThrows(IllegalArgumentException.class, () -> Json.write(deep));

        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertThrows(IllegalArgumentException.class, () -> Json.write(cyclic));
    }
}
