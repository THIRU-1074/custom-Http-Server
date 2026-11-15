package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.test.JoltTest;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MapperConcurrencyTest {

    static class Timeout extends RuntimeException {}

    @Test
    void registeringWhileServingIsSafe() throws Exception {
        App a = app();
        a.onError(Timeout.class, e -> Reply.status(503, "retry"));
        JoltTest t = JoltTest.of(a);
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> base : List.of(Object.class, String.class, Integer.class, Long.class, Short.class, Byte.class, Double.class, Float.class, Boolean.class, Character.class)) {
            Class<?> c = base;
            for (int i = 0; i < 200; i++) {
                c = Array.newInstance(c, 0).getClass();
                types.add(c);
            }
        }
        AtomicBoolean done = new AtomicBoolean();
        Thread writer =
                new Thread(
                        () -> {
                            for (Class<?> c : types) {
                                a.onError(c, e -> Reply.status(500, "x"));
                            }
                            done.set(true);
                        });
        writer.start();
        do {
            t.get("/x").expect(503);
        } while (!done.get());
        writer.join();
        assertEquals(503, t.get("/x").send().status());
    }

    private static App app() {
        App app = Jolt.create();
        app.get(
                "/x",
                (req, res) -> {
                    throw new Timeout();
                });
        return app;
    }
}
