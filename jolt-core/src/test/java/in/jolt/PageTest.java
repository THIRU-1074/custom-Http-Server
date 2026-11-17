package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageTest {

    private static Request req(String path, String query) {
        return new Request("GET", path, query, Map.of(), null, null);
    }

    private static int badRequest(Runnable r, String name) {
        HttpException e = assertThrows(HttpException.class, r::run);
        assertTrue(e.getMessage().contains("'" + name + "'"), e.getMessage());
        return e.status();
    }

    private static String link(Response res) {
        return res.headers().stream()
                .filter(h -> h.getKey().equals("Link"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    void defaultsArePageOneOfTwenty() {
        Page p = Page.of(req("/m", ""));
        assertEquals(1, p.page());
        assertEquals(20, p.size());
        assertEquals(0L, p.offset());
        assertEquals(20, p.limit());
    }

    @Test
    void readsPageAndSize() {
        Page p = Page.of(req("/m", "page=3&size=10"));
        assertEquals(3, p.page());
        assertEquals(10, p.size());
        assertEquals(20L, p.offset());
        assertEquals(10, p.limit());
    }

    @Test
    void sizeIsCappedAtTheMax() {
        assertEquals(100, Page.of(req("/m", "size=5000")).size());
        Page custom = Page.of(req("/m", "size=80"), 10, 50);
        assertEquals(50, custom.size());
        assertEquals(10, Page.of(req("/m", ""), 10, 50).size());
    }

    @Test
    void offsetDoesNotOverflow() {
        Page p = Page.of(req("/m", "page=" + Integer.MAX_VALUE + "&size=100"));
        assertEquals((Integer.MAX_VALUE - 1L) * 100L, p.offset());
    }

    @Test
    void invalidValuesAre400NamingTheParameter() {
        for (String q : new String[] {"page=0", "page=-1", "page=x", "page=1.5", "page="}) {
            assertEquals(400, badRequest(() -> Page.of(req("/m", q)), "page"), q);
        }
        for (String q : new String[] {"size=0", "size=-5", "size=abc", "size=99999999999"}) {
            assertEquals(400, badRequest(() -> Page.of(req("/m", q)), "size"), q);
        }
    }

    @Test
    void badBoundsAreRejectedAtTheCallSite() {
        assertThrows(IllegalArgumentException.class, () -> Page.of(req("/m", ""), 0, 10));
        assertThrows(IllegalArgumentException.class, () -> Page.of(req("/m", ""), 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new Page(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Page(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Page(1, 1).result(List.of(), -1));
    }

    @Test
    void resultCountsPages() {
        Page p = new Page(2, 10);
        Page.Result<String> r = p.result(List.of("a", "b"), 21);
        assertEquals(List.of("a", "b"), r.items());
        assertEquals(2, r.page());
        assertEquals(10, r.size());
        assertEquals(21L, r.total());
        assertEquals(3L, r.totalPages());
        assertEquals(0L, p.result(List.of(), 0).totalPages());
        assertEquals(2L, p.result(List.of(), 20).totalPages());
    }

    @Test
    void resultSerialisesWithTheBuiltInJson() {
        App app = Jolt.create();
        app.get("/m", p -> Page.of(p).result(List.of("x"), 41));
        JoltTest.of(app)
                .get("/m?page=2&size=20")
                .expect(200)
                .body("{\"items\":[\"x\"],\"page\":2,\"size\":20,\"total\":41,\"totalPages\":3}");
    }

    @Test
    void typedHandlersGet400ForBadInput() {
        App app = Jolt.create();
        app.get("/m", p -> Page.of(p).result(List.of(), 0));
        JoltTest.of(app).get("/m?page=0").expect(400).bodyContains("page");
        JoltTest.of(app).get("/m?size=x").expect(400).bodyContains("size");
    }

    @Test
    void linkHeaderKeepsOtherQueryParameters() {
        App app = Jolt.create();
        app.get(
                "/movies",
                (req, res) -> {
                    Page p = Page.of(req);
                    res.json(p.result(List.of(), 95).link(req, res));
                });
        JoltTest.of(app)
                .get("/movies?city=New%20York&page=3&size=10&tag=a&tag=b%26c")
                .expect(200)
                .header(
                        "Link",
                        "</movies?city=New%20York&tag=a&tag=b%26c&page=1&size=10>; rel=\"first\", "
                                + "</movies?city=New%20York&tag=a&tag=b%26c&page=2&size=10>;"
                                + " rel=\"prev\", "
                                + "</movies?city=New%20York&tag=a&tag=b%26c&page=4&size=10>;"
                                + " rel=\"next\", "
                                + "</movies?city=New%20York&tag=a&tag=b%26c&page=10&size=10>;"
                                + " rel=\"last\"");
    }

    @Test
    void linkHeaderOnEdgePages() {
        Response first = new Response(null);
        Request r1 = req("/a b", "");
        Page.of(r1).result(List.of(), 45).link(r1, first);
        assertEquals(
                "</a%20b?page=1&size=20>; rel=\"first\", </a%20b?page=2&size=20>; rel=\"next\", "
                        + "</a%20b?page=3&size=20>; rel=\"last\"",
                link(first));

        Response last = new Response(null);
        Request r3 = req("/a", "q=%C3%A9+x&page=3");
        Page.of(r3).result(List.of(), 45).link(r3, last);
        assertEquals(
                "</a?q=%C3%A9%20x&page=1&size=20>; rel=\"first\", "
                        + "</a?q=%C3%A9%20x&page=2&size=20>; rel=\"prev\", "
                        + "</a?q=%C3%A9%20x&page=3&size=20>; rel=\"last\"",
                link(last));

        Response empty = new Response(null);
        Request r0 = req("/a", "");
        Page.of(r0).result(List.of(), 0).link(r0, empty);
        assertEquals(
                "</a?page=1&size=20>; rel=\"first\", </a?page=1&size=20>; rel=\"last\"",
                link(empty));
    }

    private static String firstLink(String path) {
        App app = Jolt.create();
        app.get(
                "/*",
                (req, res) -> {
                    Page.of(req).result(List.of(), 0).link(req, res);
                    res.text("ok");
                });
        String link = JoltTest.of(app).get(path).expect(200).header("Link");
        return link.substring(0, link.indexOf('>') + 1);
    }

    @Test
    void linkBaseNeverLeavesTheHost() {
        assertEquals("</movies?page=1&size=20>", firstLink("/movies"));
        assertEquals("</evil.example/movies?page=1&size=20>", firstLink("/%2Fevil.example/movies"));
        assertEquals(
                "</evil.example/movies?page=1&size=20>", firstLink("/%2F%2Fevil.example/movies"));
        Response res = new Response(null);
        Request r = req("//evil.example/x", "");
        Page.of(r).result(List.of(), 0).link(r, res);
        assertTrue(link(res).startsWith("</evil.example/x?page=1"), link(res));
        Response root = new Response(null);
        Request slash = req("///", "");
        Page.of(slash).result(List.of(), 0).link(slash, root);
        assertTrue(link(root).startsWith("</?page=1"), link(root));
    }

    @Test
    void pageBeyondTheEndHasNoNext() {
        Response res = new Response(null);
        Request r = req("/a", "page=9");
        Page.Result<String> result = Page.of(r).result(List.<String>of(), 5).link(r, res);
        assertEquals(1L, result.totalPages());
        String link = link(res);
        assertTrue(link.contains("page=8&size=20>; rel=\"prev\""), link);
        assertTrue(!link.contains("rel=\"next\""), link);
        assertNull(link(new Response(null)));
    }
}
