package in.jolt.internal.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JsonTypesTest {

    record Item(String name) {}

    record Wild(List<? extends Item> items, Map<String, ? extends Number> nums) {}

    record Box<T>(T value) {}

    record Arr<T>(T[] generic, int[] ints, String[] strings, Item[] items) {}

    record Sets(Set<String> tags) {}

    record Unsupported(AtomicInteger counter) {}

    record Nested(List<List<Item>> grid) {}

    @Test
    void wildcardsUseTheirUpperBound() {
        Wild w =
                Json.bind(
                        Json.parse("{\"items\":[{\"name\":\"a\"}],\"nums\":{\"x\":1}}"),
                        Wild.class);
        assertEquals(List.of(new Item("a")), w.items());
        assertEquals(Map.of("x", 1L), w.nums());
    }

    @Test
    void typeVariablesUseTheirBound() {
        Box<?> b = Json.bind(Json.parse("{\"value\":{\"k\":[1]}}"), Box.class);
        assertEquals(Map.of("k", List.of(1L)), b.value());
    }

    @Test
    void arraysAreBoundFromLists() {
        Arr<?> a =
                Json.bind(
                        Json.parse(
                                "{\"generic\":[1,\"x\"],\"ints\":[1,2],\"strings\":[\"a\"],"
                                        + "\"items\":[{\"name\":\"n\"}]}"),
                        Arr.class);
        assertArrayEquals(new Object[] {1L, "x"}, a.generic());
        assertArrayEquals(new int[] {1, 2}, a.ints());
        assertArrayEquals(new String[] {"a"}, a.strings());
        assertArrayEquals(new Item[] {new Item("n")}, a.items());
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"ints\":{}}"), Arr.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"ints\":[1.5]}"), Arr.class));
    }

    @Test
    void setsAndNestedGenericsWork() {
        assertEquals(
                Set.of("a", "b"),
                Json.bind(Json.parse("{\"tags\":[\"a\",\"b\"]}"), Sets.class).tags());
        assertEquals(
                List.of(List.of(new Item("z"))),
                Json.bind(Json.parse("{\"grid\":[[{\"name\":\"z\"}]]}"), Nested.class).grid());
    }

    @Test
    void unsupportedTypesAreIllegalArgumentNeverClassCast() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"counter\":1}"), Unsupported.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"items\":{}}"), Wild.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.bind(Json.parse("{\"tags\":{}}"), Sets.class));
        assertThrows(
                IllegalArgumentException.class, () -> Json.bind(Json.parse("{}"), Thread.class));
        assertThrows(IllegalArgumentException.class, () -> Json.bind(Json.parse("5"), Item.class));
    }
}
