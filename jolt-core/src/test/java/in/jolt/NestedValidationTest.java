package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NestedValidationTest {

    record Seat(String row, Integer number) {}

    record Customer(String email) {}

    record Booking(String show, Customer customer, List<Seat> seats) {}

    record Item(String sku) {}

    record Order(List<Item> items) {}

    record Cart(Order order) {}

    private static final Validator<Seat> SEAT =
            (s, r) -> {
                r.notBlank("row", s.row());
                r.range("number", s.number(), 1, 40);
            };

    private static final Validator<Booking> BOOKING =
            (b, r) -> {
                r.notBlank("show", b.show());
                r.nested("customer", b.customer(), (c, cr) -> cr.notBlank("email", c.email()));
                r.size("seats", b.seats(), 1, 10);
                r.each("seats", b.seats(), SEAT);
            };

    private static <T> Map<String, String> errorsOf(Validator<T> v, T value) {
        return assertThrows(
                        ValidationException.class, () -> Validate.check(r -> v.validate(value, r)))
                .errors();
    }

    @Test
    void eachKeysErrorsByIndexAndField() {
        Booking b =
                new Booking(
                        "s1",
                        new Customer("a@b.c"),
                        List.of(new Seat("A", 1), new Seat("B", 2), new Seat(" ", 99)));
        assertEquals(
                Map.of(
                        "seats[2].row",
                        "must not be blank",
                        "seats[2].number",
                        "must be between 1 and 40"),
                errorsOf(BOOKING, b));
    }

    @Test
    void nestedPrefixesKeys() {
        Booking b = new Booking("s1", new Customer(""), List.of(new Seat("A", 1)));
        assertEquals(Map.of("customer.email", "must not be blank"), errorsOf(BOOKING, b));
    }

    @Test
    void nullsAreFieldErrorsNotExceptions() {
        Booking b = new Booking(null, null, null);
        assertEquals(
                List.of("show", "customer", "seats"), List.copyOf(errorsOf(BOOKING, b).keySet()));
        assertEquals("is required", errorsOf(BOOKING, b).get("customer"));
        assertEquals("is required", errorsOf(BOOKING, b).get("seats"));

        Booking holes = new Booking("s1", new Customer("x"), Arrays.asList(new Seat("A", 1), null));
        assertEquals(Map.of("seats[1]", "is required"), errorsOf(BOOKING, holes));
    }

    @Test
    void emptyCollectionRunsNoElementRules() {
        Validator<Booking> eachOnly = (b, r) -> r.each("seats", b.seats(), SEAT);
        Validate.check(r -> eachOnly.validate(new Booking(null, null, List.of()), r));
        Booking b = new Booking("s1", new Customer("x"), List.of());
        assertEquals(Map.of("seats", "size must be between 1 and 10"), errorsOf(BOOKING, b));
    }

    @Test
    void nestingComposes() {
        Validator<Cart> cart =
                (c, r) ->
                        r.nested(
                                "order",
                                c.order(),
                                (o, or) ->
                                        or.each(
                                                "items",
                                                o.items(),
                                                (i, ir) -> ir.notBlank("sku", i.sku())));
        Cart c = new Cart(new Order(List.of(new Item("ok"), new Item(""), new Item(null))));
        assertEquals(
                List.of("order.items[1].sku", "order.items[2].sku"),
                List.copyOf(errorsOf(cart, c).keySet()));

        Validator<List<List<Seat>>> grid =
                (rows, r) -> r.each("rows", rows, (row, rr) -> rr.each("seats", row, SEAT));
        List<List<Seat>> rows = List.of(List.of(), List.of(new Seat("A", 1), new Seat("", 1)));
        assertEquals(Map.of("rows[1].seats[1].row", "must not be blank"), errorsOf(grid, rows));
        assertEquals(
                Map.of("rows[0]", "is required"), errorsOf(grid, Arrays.asList((List<Seat>) null)));
    }

    @Test
    void firstMessagePerKeyWinsAcrossLevels() {
        Validator<Customer> twice =
                (c, r) -> {
                    r.error("customer.email", "taken");
                    r.nested("customer", c, (v, cr) -> cr.notBlank("email", v.email()));
                };
        assertEquals(Map.of("customer.email", "taken"), errorsOf(twice, new Customer("")));
    }

    @Test
    void typedHandlerGetsOne422WithEveryError() {
        App app = Jolt.create();
        app.post("/b", Booking.class, Validate.with(BOOKING, (p, b) -> "ok"));
        String body =
                "{\"show\":\"\",\"customer\":{\"email\":\"\"},"
                        + "\"seats\":[{\"row\":\"A\",\"number\":1},null,{\"row\":\"\",\"number\":0}]}";
        JoltTest.of(app)
                .post("/b")
                .header("Content-Type", "application/json")
                .text(body)
                .expect(422)
                .body(
                        "{\"errors\":{\"show\":\"must not be blank\","
                                + "\"customer.email\":\"must not be blank\","
                                + "\"seats[1]\":\"is required\","
                                + "\"seats[2].row\":\"must not be blank\","
                                + "\"seats[2].number\":\"must be between 1 and 40\"}}");
    }

    @Test
    void problemJsonCarriesTheNestedKeys() {
        App app = Jolt.create(JoltConfig.defaults().problemJson(true));
        app.post("/b", Booking.class, Validate.with(BOOKING, (p, b) -> "ok"));
        JoltTest.Result r =
                JoltTest.of(app)
                        .post("/b")
                        .json(new Booking("s", new Customer("x"), List.of(new Seat("", 5))))
                        .expect(422)
                        .header("Content-Type", "application/problem+json; charset=utf-8");
        assertTrue(
                r.text().endsWith("\"errors\":{\"seats[0].row\":\"must not be blank\"}}"),
                r.text());
    }
}
