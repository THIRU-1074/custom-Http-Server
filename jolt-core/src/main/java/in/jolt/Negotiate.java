package in.jolt;

import java.util.List;
import java.util.Locale;

final class Negotiate {

    private Negotiate() {}

    /** Index of the best of {@code offers} for an {@code Accept} header, or -1 if none fits. */
    static int pick(String accept, List<String> offers) {
        if (accept == null || accept.isBlank()) {
            return 0;
        }
        int best = -1;
        double bestQ = 0;
        int bestRank = 0;
        for (int i = 0; i < offers.size(); i++) {
            double[] m = match(accept, offers.get(i).toLowerCase(Locale.ROOT));
            if (m[0] > bestQ || m[0] > 0 && m[0] == bestQ && m[1] > bestRank) {
                best = i;
                bestQ = m[0];
                bestRank = (int) m[1];
            }
        }
        return best;
    }

    private static double[] match(String accept, String offer) {
        int slash = offer.indexOf('/');
        String type = offer.substring(0, slash);
        int bestRank = 0;
        double q = 0;
        for (String part : accept.split(",")) {
            String[] p = part.split(";");
            String range = p[0].strip().toLowerCase(Locale.ROOT);
            int rank = 0;
            if (range.equals(offer)) {
                rank = 3;
            } else if (range.equals(type + "/*")) {
                rank = 2;
            } else if (range.equals("*/*") || range.equals("*")) {
                rank = 1;
            }
            if (rank > bestRank) {
                bestRank = rank;
                q = weight(p);
            }
        }
        return new double[] {q, bestRank};
    }

    private static double weight(String[] params) {
        for (int i = 1; i < params.length; i++) {
            String param = params[i].strip().toLowerCase(Locale.ROOT);
            if (param.startsWith("q=")) {
                try {
                    return Double.parseDouble(param.substring(2));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 1;
    }
}
