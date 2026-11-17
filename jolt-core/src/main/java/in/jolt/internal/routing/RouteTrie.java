package in.jolt.internal.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Path trie with static segments, {@code :param}, {@code :param(regex)} and trailing {@code
 * :param?} segments, and a trailing {@code *} wildcard. Precedence is static, then constrained
 * param (registration order), then plain param, then wildcard; a constraint miss falls through.
 */
public final class RouteTrie<T> {

    public record Match<T>(T value, Map<String, String> params) {}

    private record Constrained<T>(String name, String regex, Pattern pattern, Node<T> node) {}

    private static final class Node<T> {
        final Map<String, Node<T>> statics = new HashMap<>();
        Node<T> param;
        List<Constrained<T>> constrained;
        String paramName;
        T wildcard;
        T value;
    }

    private final Node<T> root = new Node<>();

    public void insert(String pattern, T value) {
        Node<T> node = root;
        Set<String> seen = new HashSet<>();
        String[] segments = split(pattern);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.equals("*")) {
                if (i != segments.length - 1) {
                    throw new IllegalArgumentException("'*' must be the last segment: " + pattern);
                }
                node.wildcard = value;
                return;
            }
            if (seg.startsWith(":")) {
                boolean optional = isOptional(seg);
                String body = optional ? seg.substring(0, seg.length() - 1) : seg;
                if (optional && i != segments.length - 1) {
                    throw new IllegalArgumentException(
                            "Optional parameter must be the last segment: " + pattern);
                }
                int paren = body.indexOf('(');
                if (paren >= 0 && !body.endsWith(")")) {
                    throw new IllegalArgumentException("Malformed constraint in " + pattern);
                }
                String name = body.substring(1, paren < 0 ? body.length() : paren);
                if (name.isEmpty() || !seen.add(name)) {
                    throw new IllegalArgumentException(
                            "Empty or duplicate path parameter ':" + name + "' in " + pattern);
                }
                if (optional) {
                    node.value = value;
                }
                node =
                        paren < 0
                                ? plain(node, name, pattern)
                                : constrained(
                                        node,
                                        name,
                                        body.substring(paren + 1, body.length() - 1),
                                        pattern);
            } else {
                node = node.statics.computeIfAbsent(seg, k -> new Node<>());
            }
        }
        node.value = value;
    }

    private static boolean isOptional(String seg) {
        int n = seg.length();
        return seg.endsWith("?") && (seg.indexOf('(') < 0 || seg.charAt(n - 2) == ')');
    }

    private Node<T> plain(Node<T> node, String name, String pattern) {
        if (node.param == null) {
            node.param = new Node<>();
            node.paramName = name;
        } else if (!node.paramName.equals(name)) {
            throw new IllegalArgumentException(
                    "Conflicting path parameter ':"
                            + name
                            + "' in "
                            + pattern
                            + ": this position is already named ':"
                            + node.paramName
                            + "'");
        }
        return node.param;
    }

    private Node<T> constrained(Node<T> node, String name, String regex, String pattern) {
        Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Invalid constraint '" + regex + "' in " + pattern, e);
        }
        if (node.constrained == null) {
            node.constrained = new ArrayList<>();
        }
        for (Constrained<T> c : node.constrained) {
            if (c.name.equals(name) && c.regex.equals(regex)) {
                return c.node;
            }
        }
        Constrained<T> added = new Constrained<>(name, regex, compiled, new Node<>());
        node.constrained.add(added);
        return added.node;
    }

    public Match<T> find(String path) {
        Map<String, String> params = new LinkedHashMap<>();
        T found = search(root, split(path), 0, params);
        return found == null ? null : new Match<>(found, params);
    }

    private T search(Node<T> node, String[] segs, int i, Map<String, String> params) {
        if (i == segs.length) {
            return node.value != null ? node.value : node.wildcard;
        }
        Node<T> next = node.statics.get(segs[i]);
        if (next != null) {
            T hit = search(next, segs, i + 1, params);
            if (hit != null) {
                return hit;
            }
        }
        if (node.constrained != null) {
            for (Constrained<T> c : node.constrained) {
                if (c.pattern.matcher(segs[i]).matches()) {
                    params.put(c.name, segs[i]);
                    T hit = search(c.node, segs, i + 1, params);
                    if (hit != null) {
                        return hit;
                    }
                    params.remove(c.name);
                }
            }
        }
        if (node.param != null) {
            params.put(node.paramName, segs[i]);
            T hit = search(node.param, segs, i + 1, params);
            if (hit != null) {
                return hit;
            }
            params.remove(node.paramName);
        }
        if (node.wildcard != null) {
            params.put("*", String.join("/", Arrays.copyOfRange(segs, i, segs.length)));
            return node.wildcard;
        }
        return null;
    }

    private static String[] split(String path) {
        return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}
