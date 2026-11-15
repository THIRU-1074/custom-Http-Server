package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Behaviour every {@link JsonMapper} must have. Extend it and return the mapper under test. */
public abstract class JsonMapperContract {

    public record Person(String name, int age, List<String> tags) {}

    protected abstract JsonMapper mapper();

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recordSurvivesARoundTrip() {
        Person p = new Person("Ada", 36, List.of("a", "b"));
        assertEquals(p, mapper().read(mapper().write(p), Person.class));
    }

    @Test
    void nullWritesAsJsonNullAndReadsBackAsNull() {
        assertEquals("null", new String(mapper().write(null), StandardCharsets.UTF_8));
        assertNull(mapper().read(utf8("null"), Person.class));
    }

    @Test
    void absentFieldsBindAsNull() {
        Person p = mapper().read(utf8("{\"name\":\"x\",\"age\":1}"), Person.class);
        assertNull(p.tags());
    }

    @Test
    void mapsAreWrittenAsObjects() {
        assertEquals(
                "{\"k\":1}", new String(mapper().write(Map.of("k", 1)), StandardCharsets.UTF_8));
    }

    @Test
    void malformedInputIsRejectedWithIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class, () -> mapper().read(utf8("{nope"), Person.class));
        assertThrows(IllegalArgumentException.class, () -> mapper().read(utf8(""), Person.class));
    }

    @Test
    void wrongShapeIsRejectedWithIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper().read(utf8("{\"name\":\"x\",\"age\":\"old\"}"), Person.class));
    }
}
