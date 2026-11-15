package in.jolt;

import in.jolt.internal.http.Replies;
import in.jolt.internal.routing.RouteTrie;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Collects routes, middleware and mounted sub-routers. Register everything before the server
 * starts; registration is not thread-safe.
 */
public class Router {

    private record Layer(String prefix, Middleware middleware) {}

    private record Mount(String prefix, Router router) {}

    private final List<Layer> layers = new ArrayList<>();
    private final List<Mount> mounts = new ArrayList<>();
    private final Map<String, RouteTrie<Handler>> routes = new HashMap<>();
    private final List<Route> defs = new ArrayList<>();

    /** All registered routes, including those of mounted routers, in registration order. */
    public List<Route> routes() {
        List<Route> all = new ArrayList<>(defs);
        for (Mount m : mounts) {
            for (Route r : m.router.routes()) {
                all.add(new Route(r.method(), m.prefix + r.path(), r.tool()));
            }
        }
        return List.copyOf(all);
    }

    public Router get(String path, Handler h) {
        return route("GET", path, h);
    }

    public Router post(String path, Handler h) {
        return route("POST", path, h);
    }

    public Router put(String path, Handler h) {
        return route("PUT", path, h);
    }

    public Router delete(String path, Handler h) {
        return route("DELETE", path, h);
    }

    public Router patch(String path, Handler h) {
        return route("PATCH", path, h);
    }

    public Router head(String path, Handler h) {
        return route("HEAD", path, h);
    }

    public Router options(String path, Handler h) {
        return route("OPTIONS", path, h);
    }

    /** Typed GET: the result is written as JSON, {@code null} gives 204. */
    public <O> Router get(String path, Typed.Read<O> fn) {
        return get(path, (req, res) -> reply(res, fn.apply(req)));
    }

    /** Typed POST: binds the JSON body to {@code in} (400 if invalid), then writes the result. */
    public <I, O> Router post(String path, Class<I> in, Typed.Write<I, O> fn) {
        return post(path, (req, res) -> reply(res, fn.apply(req, req.body(in))));
    }

    public <I, O> Router put(String path, Class<I> in, Typed.Write<I, O> fn) {
        return put(path, (req, res) -> reply(res, fn.apply(req, req.body(in))));
    }

    public <I, O> Router patch(String path, Class<I> in, Typed.Write<I, O> fn) {
        return patch(path, (req, res) -> reply(res, fn.apply(req, req.body(in))));
    }

    /** Typed DELETE: runs {@code fn} and answers 204. */
    public Router delete(String path, Typed.Run fn) {
        return delete(
                path,
                (req, res) -> {
                    fn.run(req);
                    res.status(HttpStatus.NO_CONTENT);
                });
    }

    private static void reply(Response res, Object out) {
        if (out == null) {
            res.status(HttpStatus.NO_CONTENT);
        } else if (out instanceof Reply<?> r) {
            Replies.write(res, r);
        } else {
            res.json(out);
        }
    }

    /** Tags the most recently registered route as an MCP tool with this description. */
    public Router tool(String description) {
        if (defs.isEmpty()) {
            throw new IllegalStateException("tool() must follow a route registration");
        }
        int last = defs.size() - 1;
        Route r = defs.get(last);
        defs.set(last, new Route(r.method(), r.path(), description));
        return this;
    }

    /** Runs {@code mw} for every request, in registration order. */
    public Router use(Middleware mw) {
        return use("/", mw);
    }

    /** Runs {@code mw} for requests at or under {@code prefix}, in registration order. */
    public Router use(String prefix, Middleware mw) {
        layers.add(new Layer(normalize(prefix), mw));
        return this;
    }

    /** Mounts {@code router} under {@code prefix}. The longest matching prefix wins. */
    public Router use(String prefix, Router router) {
        mounts.add(new Mount(normalize(prefix), router));
        mounts.sort(Comparator.comparingInt((Mount m) -> m.prefix.length()).reversed());
        return this;
    }

    /** Registers {@code module}'s routes on this router. */
    public Router module(RouteModule module) {
        module.register(this);
        return this;
    }

    /** Registers {@code module}'s routes on a new router mounted under {@code prefix}. */
    public Router module(String prefix, RouteModule module) {
        Router sub = new Router();
        module.register(sub);
        return use(prefix, sub);
    }

    private Router route(String method, String path, Handler h) {
        routes.computeIfAbsent(method, k -> new RouteTrie<>()).insert(path, h);
        defs.add(new Route(method, path.startsWith("/") ? path : "/" + path, null));
        return this;
    }

    void run(Request req, Response res, String path, Middleware.Next done) throws Exception {
        List<Middleware> chain =
                layers.stream()
                        .filter(l -> matches(l.prefix, path))
                        .map(Layer::middleware)
                        .toList();
        step(chain, 0, req, res, path, done);
    }

    private void step(
            List<Middleware> chain,
            int i,
            Request req,
            Response res,
            String path,
            Middleware.Next done)
            throws Exception {
        if (i < chain.size()) {
            chain.get(i).handle(req, res, () -> step(chain, i + 1, req, res, path, done));
        } else {
            mount(0, req, res, path, done);
        }
    }

    private void mount(int from, Request req, Response res, String path, Middleware.Next done)
            throws Exception {
        int i = from;
        while (i < mounts.size() && !matches(mounts.get(i).prefix, path)) {
            i++;
        }
        if (i == mounts.size()) {
            invoke(req, res, path, done);
            return;
        }
        Mount m = mounts.get(i);
        int next = i + 1;
        String rest = path.substring(m.prefix.length());
        m.router.run(
                req, res, rest.isEmpty() ? "/" : rest, () -> mount(next, req, res, path, done));
    }

    private void invoke(Request req, Response res, String path, Middleware.Next done)
            throws Exception {
        String method = req.method();
        RouteTrie.Match<Handler> hit = find(method, path);
        if (hit == null && method.equals("HEAD")) {
            hit = find("GET", path);
        }
        if (hit != null) {
            req.params(hit.params());
            hit.value().handle(req, res);
            return;
        }
        TreeSet<String> allowed = new TreeSet<>();
        routes.forEach(
                (m, trie) -> {
                    if (trie.find(path) != null) {
                        allowed.add(m);
                    }
                });
        if (allowed.isEmpty()) {
            done.run();
            return;
        }
        res.header("Allow", String.join(", ", allowed));
        res.status(
                method.equals("OPTIONS") ? HttpStatus.NO_CONTENT : HttpStatus.METHOD_NOT_ALLOWED);
    }

    private RouteTrie.Match<Handler> find(String method, String path) {
        RouteTrie<Handler> trie = routes.get(method);
        return trie == null ? null : trie.find(path);
    }

    private static String normalize(String prefix) {
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return p.isEmpty() || p.startsWith("/") ? p : "/" + p;
    }

    private static boolean matches(String prefix, String path) {
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
