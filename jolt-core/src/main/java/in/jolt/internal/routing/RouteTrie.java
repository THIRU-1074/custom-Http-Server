package in.jolt.internal.routing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Path trie with static segments, {@code :param} segments and a trailing {@code *} wildcard. Static
 * segments win over params, and params over the wildcard.
 */
public final class RouteTrie<T> {

    public record Match<T>(T value, Map<String, String> params) {}

    private static final class Node<T> {
        final Map<String, Node<T>> statics = new HashMap<>();
        Node<T> param;
        String paramName;
        T wildcard;
        T value;
    }

    private final Node<T> root = new Node<>();

    public void insert(String pattern, T value) {
        Node<T> node = root;
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
                String name = seg.substring(1);
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
                node = node.param;
            } else {
                node = node.statics.computeIfAbsent(seg, k -> new Node<>());
            }
        }
        node.value = value;
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
