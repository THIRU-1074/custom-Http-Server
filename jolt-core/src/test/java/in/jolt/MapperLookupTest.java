package in.jolt;

import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class MapperLookupTest {

    interface Retryable {}

    interface Marker {}

    static class Base extends RuntimeException {}

    static class Mid extends Base implements Marker {}

    static class Leaf extends Mid {}

    static class Timeout extends RuntimeException implements Retryable {}

    private static App app(Throwable thrown) {
        App app = Jolt.create();
        app.get(
                "/x",
                (req, res) -> {
                    throw sneaky(thrown);
                });
        return app;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    void interfaceMapperApplies() {
        App a = app(new Timeout());
        a.onError(Retryable.class, e -> Reply.status(503, "retry"));
        JoltTest.of(a).get("/x").expect(503).bodyContains("retry");
    }

    @Test
    void closerInterfaceBeatsMoreDistantSuperclass() {
        App a = app(new Leaf());
        a.onError(Marker.class, e -> Reply.status(409, "marker"));
        a.onError(RuntimeException.class, e -> Reply.status(500, "runtime"));
        JoltTest.of(a).get("/x").expect(409).bodyContains("marker");
    }

    @Test
    void closerSuperclassBeatsMoreDistantInterface() {
        App a = app(new Leaf());
        a.onError(Marker.class, e -> Reply.status(409, "marker"));
        a.onError(Mid.class, e -> Reply.status(410, "mid"));
        JoltTest.of(a).get("/x").expect(410).bodyContains("mid");
    }

    @Test
    void tiesGoToTheFirstRegistered() {
        App a = app(new Timeout());
        a.onError(Retryable.class, e -> Reply.status(503, "retry"));
        a.onError(RuntimeException.class, e -> Reply.status(500, "runtime"));
        JoltTest.of(a).get("/x").expect(503);

        App b = app(new Timeout());
        b.onError(RuntimeException.class, e -> Reply.status(500, "runtime"));
        b.onError(Retryable.class, e -> Reply.status(503, "retry"));
        JoltTest.of(b).get("/x").expect(500);
    }
}
