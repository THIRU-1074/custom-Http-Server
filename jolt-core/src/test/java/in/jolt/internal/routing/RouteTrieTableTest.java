package in.jolt.internal.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RouteTrieTableTest {

    private final RouteTrie<String> trie = new RouteTrie<>();

    @BeforeEach
    void routes() {
        for (String p :
                new String[] {
                    "/",
                    "/users",
                    "/users/:id",
                    "/users/me",
                    "/users/:id/posts/:post",
                    "/files/*",
                    "/files/readme",
                    "/a/b/c",
                    "/a/:x/d"
                }) {
            trie.insert(p, p);
        }
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(
            delimiter = '|',
            nullValues = "-",
            value = {
                "/|/|-",
                "/users|/users|-",
                "/users/|/users|-",
                "/users/42|/users/:id|id=42",
                "/users/me|/users/me|-",
                "/users/me/posts/7|/users/:id/posts/:post|id=me;post=7",
                "/files/readme|/files/readme|-",
                "/files/a/b.txt|/files/*|*=a/b.txt",
                "/files/x|/files/*|*=x",
                "/a/b/c|/a/b/c|-",
                "/a/b/d|/a/:x/d|x=b",
                "/a/q/d|/a/:x/d|x=q",
                "/users/1/posts|-|-",
                "/nothing|-|-",
                "/users/1/2/3|-|-"
            })
    void resolves(String path, String route, String params) {
        var m = trie.find(path);
        if (route == null) {
            assertNull(m);
            return;
        }
        assertEquals(route, m.value());
        Map<String, String> expected = new LinkedHashMap<>();
        if (params != null) {
            for (String kv : params.split(";")) {
                String[] p = kv.split("=", 2);
                expected.put(p[0], p[1]);
            }
        }
        assertEquals(expected, m.params());
    }
}
