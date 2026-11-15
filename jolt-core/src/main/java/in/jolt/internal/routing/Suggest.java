package in.jolt.internal.routing;

import in.jolt.Route;
import java.util.Arrays;
import java.util.List;

/** Finds the registered route closest to a request that matched nothing. */
public final class Suggest {

    private Suggest() {}

    /** Returns {@code "METHOD /path"} for the nearest route, or {@code null} if none is close. */
    public static String nearest(List<Route> routes, String method, String path) {
        String[] segs = split(path);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Route r : routes) {
            String[] pat = split(r.path());
            if (pat.length != segs.length) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pat.length; i++) {
                sb.append('/').append(pat[i].startsWith(":") ? segs[i] : pat[i]);
            }
            int d = distance(sb.toString(), path);
            int score = d * 2 + (r.method().equals(method) ? 0 : 1);
            if (d <= Math.max(2, path.length() / 3) && score < bestScore && score > 0) {
                bestScore = score;
                best = r.method() + " " + r.path();
            }
        }
        return best;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static String[] split(String p) {
        return Arrays.stream(p.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}
