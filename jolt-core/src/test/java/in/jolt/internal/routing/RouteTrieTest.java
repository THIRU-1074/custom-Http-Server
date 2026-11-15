package in.jolt.internal.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteTrieTest {

    @Test
    void matchesStaticAndParams() {
        var trie = new RouteTrie<String>();
        trie.insert("/users/:id", "user");
        trie.insert("/users/me", "me");
        trie.insert("/a/:x/b/:y", "ab");

        assertEquals("user", trie.find("/users/42").value());
        assertEquals(Map.of("id", "42"), trie.find("/users/42").params());
        assertEquals("me", trie.find("/users/me").value());
        assertEquals(Map.of("x", "1", "y", "2"), trie.find("/a/1/b/2").params());
    }

    @Test
    void wildcardCapturesRestAndLosesToMoreSpecific() {
        var trie = new RouteTrie<String>();
        trie.insert("/static/*", "files");
        trie.insert("/static/logo", "logo");

        assertEquals("logo", trie.find("/static/logo").value());
        assertEquals("files", trie.find("/static/css/app.css").value());
        assertEquals("css/app.css", trie.find("/static/css/app.css").params().get("*"));
    }

    @Test
    void backtracksFromStaticDeadEndToParam() {
        var trie = new RouteTrie<String>();
        trie.insert("/a/b/c", "static");
        trie.insert("/a/:x/d", "param");

        assertEquals("param", trie.find("/a/b/d").value());
        assertEquals(Map.of("x", "b"), trie.find("/a/b/d").params());
    }

    @Test
    void ignoresTrailingAndRepeatedSlashes() {
        var trie = new RouteTrie<String>();
        trie.insert("/x/", "x");
        trie.insert("/", "root");

        assertEquals("x", trie.find("/x").value());
        assertEquals("x", trie.find("//x//").value());
        assertEquals("root", trie.find("/").value());
    }

    @Test
    void missesReturnNull() {
        var trie = new RouteTrie<String>();
        trie.insert("/a/:id", "a");

        assertNull(trie.find("/b"));
        assertNull(trie.find("/a"));
        assertNull(trie.find("/a/1/2"));
    }

    @Test
    void wildcardMustBeLast() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteTrie<String>().insert("/a/*/b", "x"));
    }
}
