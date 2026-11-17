package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ValidationTest {

    record Todo(String title, Integer prio, List<String> tags, String code, String kind) {}

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private static final Validator<Todo> TODO =
            (t, r) -> {
                r.notBlank("title", t.title());
                r.range("prio", t.prio(), 1, 5);
                r.size("tags", t.tags(), 1, 3);
                r.matches("code", t.code(), CODE);
                r.oneOf("kind", t.kind(), "bug", "task");
            };

    private static Map<String, String> errorsOf(Validator<Todo> v, Todo t) {
        return assertThrows(ValidationException.class, () -> Validate.check(r -> v.validate(t, r)))
                .errors();
    }

    @Test
    void allFieldErrorsComeBackInOneResponse() {
        App app = Jolt.create();
        app.post("/t", Todo.class, Validate.with(TODO, (p, t) -> "ok"));
        JoltTest.of(app)
                .post("/t")
                .json(new Todo(" ", 9, List.of(), "abc", "epic"))
                .expect(422)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(
                        "{\"errors\":{\"title\":\"must not be blank\","
                                + "\"prio\":\"must be between 1 and 5\","
                                + "\"tags\":\"size must be between 1 and 3\","
                                + "\"code\":\"has an invalid format\","
                                + "\"kind\":\"must be one of: bug, task\"}}");
    }

    @Test
    void validBodyReachesTheHandler() {
        App app = Jolt.create();
        app.post("/t", Todo.class, Validate.with(TODO, (p, t) -> Reply.created(t.title())));
        JoltTest.of(app)
                .post("/t")
                .json(new Todo("x", 2, List.of("a"), "ABC", "bug"))
                .expect(201)
                .body("\"x\"");
    }

    @Test
    void withComposesWithRouteMiddlewareAndOtherVerbs() {
        App app = Jolt.create();
        Middleware key =
                (req, res, next) -> {
                    if (req.header("X-Key") == null) {
                        res.status(401).text("no");
                        return;
                    }
                    next.run();
                };
        app.put("/t", key, Todo.class, Validate.with(TODO, (p, t) -> t.title()));
        JoltTest t = JoltTest.of(app);
        Todo bad = new Todo("", 1, List.of("a"), "ABC", "bug");
        t.put("/t").json(bad).expect(401);
        t.put("/t").json(bad).header("X-Key", "k").expect(422).bodyContains("title");
    }

    @Test
    void checkWorksInsideAHandler() {
        App app = Jolt.create();
        app.get(
                "/q",
                (req, res) -> {
                    Validate.check(r -> r.notBlank("q", req.query("q")));
                    res.text("fine");
                });
        JoltTest t = JoltTest.of(app);
        t.get("/q?q=a").expect(200).body("fine");
        t.get("/q").expect(422).body("{\"errors\":{\"q\":\"must not be blank\"}}");
    }

    @Test
    void validatorsCompose() {
        Validator<Todo> a = (t, r) -> r.notBlank("title", t.title());
        Validator<Todo> b = (t, r) -> r.range("prio", t.prio(), 1, 5);
        Map<String, String> e = errorsOf(a.and(b), new Todo("", 0, null, null, null));
        assertEquals(List.of("title", "prio"), List.copyOf(e.keySet()));
    }

    @Test
    void firstMessagePerFieldWins() {
        Rules r = new Rules();
        r.notBlank("a", "").error("a", "later").error("b", "custom");
        assertEquals(Map.of("a", "must not be blank", "b", "custom"), r.errors());
    }

    @Test
    void missingValuesAreErrorsNotCrashes() {
        Map<String, String> e = errorsOf(TODO, new Todo(null, null, null, null, null));
        assertEquals("must not be blank", e.get("title"));
        assertEquals("is required", e.get("prio"));
        assertEquals("is required", e.get("tags"));
        assertEquals("is required", e.get("code"));
        assertEquals("is required", e.get("kind"));
    }

    @Test
    void rulesAcceptBoundariesAndMixedNumbers() {
        Rules r = new Rules();
        r.range("lo", 1, 1, 5).range("hi", 5L, 1, 5).range("d", 2.5, 1.0, 3.0);
        r.size("s", "ab", 2, 2).size("c", List.of(1), 1, 1).oneOf("o", 3, 1, 2, 3);
        assertTrue(r.errors().isEmpty());
        r.range("d", 3.5, 1.0, 3.0).range("n", 0, 1, 5).size("s2", "a", 2, 3);
        assertEquals(List.of("d", "n", "s2"), List.copyOf(r.errors().keySet()));
        assertEquals("must be between 1.0 and 3.0", r.errors().get("d"));
        assertEquals("size must be between 2 and 3", r.errors().get("s2"));
    }

    @Test
    void exceptionIsAnImmutable422HttpException() {
        ValidationException e = new ValidationException(Map.of("a", "bad"));
        assertInstanceOf(HttpException.class, e);
        assertEquals(422, e.status());
        assertEquals("Validation failed", e.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> e.errors().put("b", "c"));
    }

    @Test
    void anErrorMapperOverridesTheDefaultReply() {
        App app = Jolt.create();
        app.onError(
                ValidationException.class,
                e -> Reply.status(400, Map.of("bad", e.errors().size())));
        app.post("/t", Todo.class, Validate.with(TODO, (p, t) -> "ok"));
        JoltTest.of(app)
                .post("/t")
                .json(new Todo("", 1, List.of("a"), "ABC", "bug"))
                .expect(400)
                .body("{\"bad\":1}");
    }

    @Test
    void nullBodyIs400BeforeValidatorOrHandler() {
        App app = Jolt.create();
        app.post(
                "/t",
                Todo.class,
                Validate.with(
                        (t, r) -> {
                            throw new AssertionError("validator ran");
                        },
                        (p, t) -> {
                            throw new AssertionError("handler ran");
                        }));
        JoltTest.of(app).post("/t").text("null").expect(400).body("Body is required");
    }

    @Test
    void rangeComparesExactlyBeyondDoublePrecision() {
        var big = new java.math.BigDecimal("9007199254740993");
        var max = new java.math.BigDecimal("9007199254740992");
        Rules r = new Rules();
        r.range("over", big, 0, max);
        assertEquals("must be between 0 and 9007199254740992", r.errors().get("over"));
        r.range("ok", max, 0, max).range("long", Long.MAX_VALUE, 0L, Long.MAX_VALUE - 1);
        assertEquals(List.of("over", "long"), List.copyOf(r.errors().keySet()));
        r.range("mix", new java.math.BigInteger("5"), 1, 5.5).range("nan", Double.NaN, 0.0, 1.0);
        assertEquals(List.of("over", "long", "nan"), List.copyOf(r.errors().keySet()));
        r.range("dbl", 0.1 + 0.2, 0.0, 0.3);
        assertEquals("must be between 0.0 and 0.3", r.errors().get("dbl"));
    }

    @Test
    void rangeRejectsInvertedBoundsAtTheCall() {
        assertThrows(IllegalArgumentException.class, () -> new Rules().range("x", 3, 5, 1));
        assertThrows(IllegalArgumentException.class, () -> new Rules().range("x", null, 5, 1));
    }
}
