package strix.harness;

/**
 * The exact GSPRT for the pentanomial model, replacing the normal approximation.
 *
 * <h2>What "generalized" means</h2>
 * Plain SPRT compares two fully specified distributions. Here the hypotheses only
 * fix the <em>mean</em> score (elo0 and elo1); the shape of the distribution is a
 * nuisance parameter nobody specified. GSPRT handles that by replacing each
 * likelihood with its maximum over all distributions whose mean matches the
 * hypothesis. That maximum is a constrained MLE, and computing it is the whole
 * job of this class.
 *
 * <h2>The constrained MLE</h2>
 * Maximising the multinomial log-likelihood subject to the mean being {@code s}
 * gives, by Lagrange multipliers,
 *
 * <pre>    p_i = phat_i / (1 + theta * (s_i - s))</pre>
 *
 * where theta is whatever makes the mean come out to {@code s}. That reduces to a
 * one-dimensional root find, which is monotone, so bisection is enough.
 *
 * <h2>Small samples, which are the real hazard</h2>
 * The normal approximation this replaced divided by an observed variance that is
 * zero when early pairs all score alike, producing an LLR of -3.6e9 after two pairs.
 * The exact form has no such division, but it has its own small-sample failure: with
 * two pairs the observed mass sits in one or two buckets, and there may be NO
 * distribution on that support with the hypothesised mean. The bisection then runs
 * to the edge of its bracket and returns nonsense. Observed directly: an engine
 * losing at -88.7 Elo produced an LLR of +15.71 after two pairs.
 *
 * The fix is a Jeffreys prior of 0.5 pseudo-counts per bucket. Support is then
 * always full, so a mean anywhere in (0, 1) is always achievable, and small samples
 * are damped toward "no information" instead of producing confident garbage. The
 * prior washes out as real counts accumulate.
 *
 * The lesson generalises: small samples were the hazard in both formulations, and
 * only the synthetic test in SprtTest found either one. See docs/adr/0010.
 */
public final class Gsprt {

    /** Normalized per-game score for each pentanomial bucket: 0, 0.5, 1, 1.5, 2 points over two games. */
    private static final double[] SCORES = {0.0, 0.25, 0.5, 0.75, 1.0};

    /**
     * Jeffreys prior for a multinomial. Guarantees full support so the mean
     * constraint is always satisfiable, and damps tiny samples toward no
     * information rather than confident nonsense.
     */
    private static final double PRIOR = 0.5;

    private Gsprt() {}

    public static double expectedScore(double elo) {
        return 1.0 / (1.0 + Math.pow(10.0, -elo / 400.0));
    }

    public static double eloOf(double score) {
        if (score <= 0.0) return -800;
        if (score >= 1.0) return 800;
        return -400.0 * Math.log10(1.0 / score - 1.0);
    }

    /**
     * Log-likelihood ratio of H1 (mean = score1) against H0 (mean = score0),
     * given pentanomial counts.
     */
    public static double llr(long[] counts, double score0, double score1) {
        long raw = 0;
        for (long c : counts) raw += c;
        if (raw == 0) return 0.0;

        double total = raw + 5 * PRIOR;
        double[] smoothed = new double[5];
        double[] phat = new double[5];
        for (int i = 0; i < 5; i++) {
            smoothed[i] = counts[i] + PRIOR;
            phat[i] = smoothed[i] / total;
        }

        double[] p0 = constrainedMle(phat, score0);
        double[] p1 = constrainedMle(phat, score1);
        if (p0 == null || p1 == null) return 0.0;

        double llr = 0.0;
        for (int i = 0; i < 5; i++) {
            llr += smoothed[i] * (Math.log(p1[i]) - Math.log(p0[i]));
        }
        return llr;
    }

    /**
     * The distribution closest to {@code phat} in likelihood whose mean is exactly
     * {@code target}, or null if the observations have no shape to work with (all
     * mass in one bucket).
     */
    private static double[] constrainedMle(double[] phat, double target) {
        int support = 0;
        for (double p : phat) if (p > 0) support++;
        if (support < 2) return null;

        // 1 + theta*(s_i - target) must stay positive wherever phat_i > 0.
        double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 5; i++) {
            if (phat[i] <= 0) continue;
            double d = SCORES[i] - target;
            if (d > 0) lo = Math.max(lo, -1.0 / d);
            else if (d < 0) hi = Math.min(hi, -1.0 / d);
        }
        if (lo == Double.NEGATIVE_INFINITY) lo = -1e9;
        if (hi == Double.POSITIVE_INFINITY) hi = 1e9;
        // Stay strictly inside, or the log blows up at the endpoints.
        double span = hi - lo;
        lo += span * 1e-12;
        hi -= span * 1e-12;

        // f is monotone decreasing in theta, and f(0) = observedMean - target.
        for (int iter = 0; iter < 200; iter++) {
            double mid = 0.5 * (lo + hi);
            double f = meanGap(phat, target, mid);
            if (f > 0) lo = mid; else hi = mid;
        }
        double theta = 0.5 * (lo + hi);

        double[] p = new double[5];
        double sum = 0;
        for (int i = 0; i < 5; i++) {
            if (phat[i] <= 0) continue;
            double denom = 1 + theta * (SCORES[i] - target);
            if (denom <= 0) return null;
            p[i] = phat[i] / denom;
            sum += p[i];
        }
        if (sum <= 0) return null;
        for (int i = 0; i < 5; i++) p[i] /= sum;
        return p;
    }

    private static double meanGap(double[] phat, double target, double theta) {
        double acc = 0;
        for (int i = 0; i < 5; i++) {
            if (phat[i] <= 0) continue;
            double d = SCORES[i] - target;
            double denom = 1 + theta * d;
            if (denom <= 0) return Double.NaN;
            acc += phat[i] * d / denom;
        }
        return acc;
    }
}
