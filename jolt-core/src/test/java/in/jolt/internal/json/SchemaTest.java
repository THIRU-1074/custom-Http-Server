package in.jolt.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchemaTest {

    enum Status {
        OPEN,
        DONE
    }

    record Tag(String name) {}

    record Item(
            String title,
            int count,
            long big,
            double ratio,
            BigDecimal price,
            boolean done,
            Status status,
            UUID id,
            LocalDate due,
            Instant at,
            List<Tag> tags,
            Set<String> labels,
            int[] scores,
            Map<String, Integer> extra,
            Optional<String> note,
            Object anything) {}

    record Node(String value, List<Node> children) {}

    private static String schema(Class<?> type) {
        return Json.write(Schema.of(type));
    }

    @Test
    void scalarsMapToJsonTypes() {
        assertEquals("{\"type\":\"string\"}", schema(String.class));
        assertEquals("{\"type\":\"integer\"}", schema(Integer.class));
        assertEquals("{\"type\":\"number\"}", schema(float.class));
        assertEquals("{\"type\":\"boolean\"}", schema(boolean.class));
        assertEquals("{}", schema(Object.class));
    }

    @Test
    void recordComponentsBecomeProperties() {
        assertEquals(
                "{\"type\":\"object\",\"properties\":{"
                        + "\"title\":{\"type\":\"string\"},"
                        + "\"count\":{\"type\":\"integer\"},"
                        + "\"big\":{\"type\":\"integer\"},"
                        + "\"ratio\":{\"type\":\"number\"},"
                        + "\"price\":{\"type\":\"number\"},"
                        + "\"done\":{\"type\":\"boolean\"},"
                        + "\"status\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"]},"
                        + "\"id\":{\"type\":\"string\",\"format\":\"uuid\"},"
                        + "\"due\":{\"type\":\"string\",\"format\":\"date\"},"
                        + "\"at\":{\"type\":\"string\",\"format\":\"date-time\"},"
                        + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
                        + "\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}},"
                        + "\"labels\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                        + "\"scores\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}},"
                        + "\"extra\":{\"type\":\"object\",\"additionalProperties\":"
                        + "{\"type\":\"integer\"}},"
                        + "\"note\":{\"type\":\"string\"},"
                        + "\"anything\":{}},"
                        + "\"required\":[\"title\",\"count\",\"big\",\"ratio\",\"price\",\"done\","
                        + "\"status\",\"id\",\"due\",\"at\",\"tags\",\"labels\",\"scores\",\"extra\","
                        + "\"anything\"]}",
                schema(Item.class));
    }

    @Test
    void recursiveRecordStopsAtTheCycle() {
        assertEquals(
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"},"
                        + "\"children\":{\"type\":\"array\",\"items\":{}}},"
                        + "\"required\":[\"value\",\"children\"]}",
                schema(Node.class));
    }

    @Test
    void siblingUseOfTheSameRecordIsNotTreatedAsACycle() {
        record Pair(Tag left, Tag right) {}
        String tag =
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},"
                        + "\"required\":[\"name\"]}";
        assertEquals(
                "{\"type\":\"object\",\"properties\":{\"left\":"
                        + tag
                        + ",\"right\":"
                        + tag
                        + "},\"required\":[\"left\",\"right\"]}",
                schema(Pair.class));
    }
}
