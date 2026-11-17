package in.jolt.internal.view;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal HTML templates: {@code {{x}}}, {@code {{{x}}}}, each, if, partials and a layout. */
public final class Views {

    private static final int MAX_DEPTH = 16;
    private static final Object NULL = new Object();

    private sealed interface Node {}

    private record Text(String text) implements Node {}

    private record Var(String path, boolean raw) implements Node {}

    private record Each(String path, List<Node> body) implements Node {}

    private record If(String path, List<Node> body) implements Node {}

    private record Include(String name) implements Node {}

    private record Cached(Path file, FileTime modified, List<Node> nodes) {}

    private final Path root;
    private final String layout;
    private final java.util.concurrent.atomic.AtomicLong checks =
            new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Templates are {@code .html} files under {@code root}; {@code layout}, if not null, names a
     * template that wraps every page and prints the page with {@code {{{content}}}}.
     *
     * @throws IllegalArgumentException if the root cannot be resolved
     */
    public Views(Path root, String layout) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Views root not found: " + root, e);
        }
        this.layout = layout;
    }

    /**
     * Renders the template called {@code name} (no extension) with {@code model}.
     *
     * @throws IllegalArgumentException if the name leaves the root, a template is missing or
     *     malformed, or partials nest too deeply
     */
    public String render(String name, Map<String, ?> model) {
        Map<String, Object> scope = new HashMap<>(model == null ? Map.of() : model);
        Map<String, List<Node>> loaded = new HashMap<>();
        StringBuilder out = new StringBuilder();
        draw(load(name, loaded), scopes(scope), out, 0, loaded);
        if (layout == null || layout.equals(name)) {
            return out.toString();
        }
        scope.put("content", out.toString());
        StringBuilder page = new StringBuilder();
        draw(load(layout, loaded), scopes(scope), page, 0, loaded);
        return page.toString();
    }

    private static Deque<Object> scopes(Object first) {
        Deque<Object> d = new ArrayDeque<>();
        d.push(first);
        return d;
    }

    /** Number of template file checks made so far; for tests. */
    public long fileChecks() {
        return checks.get();
    }

    private List<Node> load(String name, Map<String, List<Node>> loaded) {
        List<Node> known = loaded.get(name);
        if (known == null) {
            known = load(name);
            loaded.put(name, known);
        }
        return known;
    }

    private List<Node> load(String name) {
        checks.incrementAndGet();
        Cached c = cache.get(name);
        try {
            if (c != null && Files.getLastModifiedTime(c.file).equals(c.modified)) {
                return c.nodes;
            }
        } catch (NoSuchFileException e) {
            cache.remove(name);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read template: " + name, e);
        }
        Path file = resolve(name);
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            List<Node> nodes = parse(Files.readString(file), name);
            cache.put(name, new Cached(file, modified, nodes));
            return nodes;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read template: " + name, e);
        }
    }

    private Path resolve(String name) {
        if (name.isEmpty()
                || name.startsWith("/")
                || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Illegal template name");
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                throw new IllegalArgumentException("Illegal template name");
            }
        }
        try {
            Path file = root.resolve(name.endsWith(".html") ? name : name + ".html").normalize();
            if (!file.startsWith(root)) {
                throw new IllegalArgumentException("Illegal template name");
            }
            Path real = file.toRealPath();
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException("Illegal template name");
            }
            if (!Files.isRegularFile(real)) {
                throw new IllegalArgumentException("Template not found: " + name);
            }
            return real;
        } catch (java.nio.file.InvalidPathException | IOException e) {
            throw new IllegalArgumentException("Template not found: " + name);
        }
    }

    private static List<Node> parse(String src, String name) {
        Deque<List<Node>> bodies = new ArrayDeque<>();
        Deque<String[]> open = new ArrayDeque<>();
        List<Node> current = new ArrayList<>();
        int pos = 0;
        while (true) {
            int start = src.indexOf("{{", pos);
            if (start < 0) {
                current.add(new Text(src.substring(pos)));
                break;
            }
            current.add(new Text(src.substring(pos, start)));
            boolean raw = src.startsWith("{{{", start);
            String close = raw ? "}}}" : "}}";
            int tagStart = start + (raw ? 3 : 2);
            int end = src.indexOf(close, tagStart);
            if (end < 0) {
                throw new IllegalArgumentException("Template " + name + ": unclosed tag");
            }
            String tag = src.substring(tagStart, end).strip();
            pos = end + close.length();
            if (raw) {
                current.add(new Var(tag, true));
            } else if (tag.startsWith("#each ") || tag.startsWith("#if ")) {
                int space = tag.indexOf(' ');
                open.push(new String[] {tag.substring(1, space), tag.substring(space + 1).strip()});
                bodies.push(current);
                current = new ArrayList<>();
            } else if (tag.equals("/each") || tag.equals("/if")) {
                if (open.isEmpty() || !open.peek()[0].equals(tag.substring(1))) {
                    throw new IllegalArgumentException("Template " + name + ": unexpected " + tag);
                }
                String[] block = open.pop();
                List<Node> body = current;
                current = bodies.pop();
                current.add(
                        block[0].equals("each")
                                ? new Each(block[1], body)
                                : new If(block[1], body));
            } else if (tag.startsWith(">")) {
                current.add(new Include(tag.substring(1).strip()));
            } else {
                current.add(new Var(tag, false));
            }
        }
        if (!open.isEmpty()) {
            throw new IllegalArgumentException(
                    "Template " + name + ": unclosed {{#" + open.peek()[0] + "}}");
        }
        return current;
    }

    private void draw(
            List<Node> nodes,
            Deque<Object> scopes,
            StringBuilder out,
            int depth,
            Map<String, List<Node>> loaded) {
        for (Node node : nodes) {
            switch (node) {
                case Text t -> out.append(t.text);
                case Var v -> {
                    Object value = lookup(scopes, v.path);
                    if (value != null) {
                        out.append(v.raw ? value.toString() : escape(value.toString()));
                    }
                }
                case If i -> {
                    if (truthy(lookup(scopes, i.path))) {
                        draw(i.body, scopes, out, depth, loaded);
                    }
                }
                case Each e -> {
                    for (Iterator<?> it = items(lookup(scopes, e.path)); it.hasNext(); ) {
                        Object item = it.next();
                        scopes.push(item == null ? NULL : item);
                        draw(e.body, scopes, out, depth, loaded);
                        scopes.pop();
                    }
                }
                case Include inc -> {
                    if (depth >= MAX_DEPTH) {
                        throw new IllegalArgumentException("Partials nested too deeply");
                    }
                    draw(load(inc.name, loaded), scopes, out, depth + 1, loaded);
                }
            }
        }
    }

    private static Object lookup(Deque<Object> scopes, String path) {
        if (path.equals(".") || path.equals("this")) {
            Object top = scopes.peek();
            return top == NULL ? null : top;
        }
        String[] parts = path.split("\\.");
        for (Object scope : scopes) {
            if (has(scope, parts[0])) {
                Object value = get(scope, parts[0]);
                for (int i = 1; i < parts.length && value != null; i++) {
                    value = get(value, parts[i]);
                }
                return value;
            }
        }
        return null;
    }

    private static final ClassValue<Map<String, Method>> ACCESSORS =
            new ClassValue<>() {
                @Override
                protected Map<String, Method> computeValue(Class<?> type) {
                    Map<String, Method> out = new HashMap<>();
                    if (type.isRecord()) {
                        for (RecordComponent rc : type.getRecordComponents()) {
                            Method m = rc.getAccessor();
                            try {
                                m.setAccessible(true);
                            } catch (RuntimeException e) {
                                continue;
                            }
                            out.put(rc.getName(), m);
                        }
                    }
                    return out;
                }
            };

    private static boolean has(Object scope, String key) {
        if (scope instanceof Map<?, ?> m) {
            return m.containsKey(key);
        }
        return scope != null && ACCESSORS.get(scope.getClass()).containsKey(key);
    }

    private static Object get(Object scope, String key) {
        if (scope instanceof Map<?, ?> m) {
            return m.get(key);
        }
        Method accessor = scope == null ? null : ACCESSORS.get(scope.getClass()).get(key);
        try {
            return accessor == null ? null : accessor.invoke(scope);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static boolean truthy(Object v) {
        return switch (v) {
            case null -> false;
            case Boolean b -> b;
            case CharSequence s -> s.length() > 0;
            case Collection<?> c -> !c.isEmpty();
            case Map<?, ?> m -> !m.isEmpty();
            default -> !v.getClass().isArray() || Array.getLength(v) > 0;
        };
    }

    private static Iterator<?> items(Object v) {
        if (v instanceof Iterable<?> it) {
            return it.iterator();
        }
        if (v != null && v.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < Array.getLength(v); i++) {
                list.add(Array.get(v, i));
            }
            return list.iterator();
        }
        return List.of().iterator();
    }

    private static String escape(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            String rep =
                    switch (s.charAt(i)) {
                        case '&' -> "&amp;";
                        case '<' -> "&lt;";
                        case '>' -> "&gt;";
                        case '"' -> "&quot;";
                        case '\'' -> "&#39;";
                        default -> null;
                    };
            if (rep != null && sb == null) {
                sb = new StringBuilder(s.length() + 16).append(s, 0, i);
            }
            if (sb != null) {
                if (rep != null) {
                    sb.append(rep);
                } else {
                    sb.append(s.charAt(i));
                }
            }
        }
        return sb == null ? s : sb.toString();
    }
}
