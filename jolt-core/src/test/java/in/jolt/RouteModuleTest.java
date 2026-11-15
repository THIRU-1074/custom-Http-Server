package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class RouteModuleTest {

    private record Greeting(String word) implements RouteModule {
        @Override
        public void register(Router router) {
            router.get("/hello", (req, res) -> res.text(word));
        }
    }

    @Test
    void twoModulesWithDifferentCollaboratorsShareOneApp() {
        App app = Jolt.create();
        app.module("/en", new Greeting("hello"));
        app.module("/fr", new Greeting("bonjour"));
        JoltTest t = JoltTest.of(app);
        t.get("/en/hello").expect(200).body("hello");
        t.get("/fr/hello").expect(200).body("bonjour");
    }

    @Test
    void moduleWithoutPrefixRegistersOnTheRouterItself() {
        App app = Jolt.create();
        app.module(new Greeting("hi"));
        JoltTest t = JoltTest.of(app);
        t.get("/hello").expect(200).body("hi");
        t.get("/en/hello").expect(404);
    }

    @Test
    void moduleCanBeTestedAloneOnABareApp() {
        App app = Jolt.create();
        new Greeting("solo").register(app);
        JoltTest.of(app).get("/hello").expect(200).body("solo");
    }

    @Test
    void prefixedModuleRoutesAreListed() {
        App app = Jolt.create();
        app.module("/v1", new Greeting("x"));
        assertEquals("/v1/hello", app.routes().get(0).path());
    }
}
