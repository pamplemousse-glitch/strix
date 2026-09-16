package strix.harness;

/**
 * Sequential Probability Ratio Test: decide whether a change helped, stopping as
 * soon as the evidence is conclusive rather than after a fixed number of games.
 *
 * Two hypotheses, H0 "the patch is worth elo0" and H1 "the patch is worth elo1".
 * Two error tolerances, alpha (accept a worthless patch) and beta (reject a good
 * one). After each result the log-likelihood ratio moves, and when it crosses one
 * of two fixed bounds the test is over.
 *
 * The guarantee is a bound on error rates, and it holds however long the test
 * runs. That is what makes continuous peeking legitimate here when it would be
 * cheating anywhere else: the stopping rule is inside the proof. Deviate from it
 * (stop early, or keep collecting after a crossing) and the bound no longer holds.
 *
 * <h2>Why games come in pairs</h2>
 * Results are recorded as PAIRS: the same opening played twice with colours
 * reversed. The pair, not the game, is the unit of observation, which removes the
 * variance from one side having a better opening. Five possible pair scores gives
 * this the name "pentanomial". A half pair is not an observation this model can
 * consume, which is why the runner batches games in even numbers and discards an
 * incomplete batch rather than splitting one.
 *
 * <h2>What this implementation is</h2>
 * The normal-approximation GSPRT, using the observed mean and variance of the pair
 * score. Fishtest uses a more exact formulation in terms of normalized Elo. The
 * approximation is standard, and it is verified in SprtTest by feeding it
 * synthetic results with a known win rate and checking it reaches the correct
 * verdict at the correct rate.
 */
public final class Sprt {

    public enum Verdict { CONTINUE, H1_ACCEPTED, H0_ACCEPTED }

    /**
     * No verdict before this many pairs, whatever the LLR says.
     *
     * The normal approximation needs an observed variance, and with a handful of
     * samples that variance can be zero (every pair scoring identically). Dividing
     * by it then produces an LLR in the billions and an instant, confident, wrong
     * verdict. Observed directly: a +40 Elo patch was rejected after 2 pairs with
     * an LLR of -3.6e9.
     */
    public static final int MIN_PAIRS = 16;

    public final double elo0, elo1, lowerBound, upperBound;

    /** Pair outcome counts, indexed by pair score in half-points: 0, 0.5, 1, 1.5, 2. */
    private final long[] pairs = new long[5];

    public Sprt(double elo0, double elo1, double alpha, double beta) {
        this.elo0 = elo0;
        this.elo1 = elo1;
        this.lowerBound = Math.log(beta / (1 - alpha));
        this.upperBound = Math.log((1 - beta) / alpha);
    }

    /** Fishtest's usual settings: is this worth at least 0 Elo, or at least 5? */
    public static Sprt standard() {
        return new Sprt(0.0, 5.0, 0.05, 0.05);
    }

    /** @param pairScore 0, 1, 2, 3 or 4, meaning 0 to 2 points in half-point steps. */
    public void record(int pairScore) {
        pairs[pairScore]++;
    }

    public long pairCount() {
        long n = 0;
        for (long p : pairs) n += p;
        return n;
    }

    public long gameCount() { return pairCount() * 2; }

    private static double expectedScore(double elo) {
        return 1.0 / (1.0 + Math.pow(10.0, -elo / 400.0));
    }

    /** Observed mean score per game, in [0, 1]. */
    public double score() {
        long n = pairCount();
        if (n == 0) return 0.5;
        double total = 0;
        for (int i = 0; i < 5; i++) total += pairs[i] * (i / 4.0);
        return total / n;
    }

    /** Observed Elo difference implied by the current score. */
    public double elo() {
        double s = score();
        if (s <= 0.0) return -800;
        if (s >= 1.0) return 800;
        return -400.0 * Math.log10(1.0 / s - 1.0);
    }

    public double llr() {
        long n = pairCount();
        if (n < MIN_PAIRS) return 0.0;

        double mean = score();
        double variance = 0;
        for (int i = 0; i < 5; i++) {
            double d = (i / 4.0) - mean;
            variance += pairs[i] * d * d;
        }
        variance /= n;

        // Every pair scored identically. That is not infinite certainty, it is
        // not enough information yet. Say so rather than dividing by ~zero.
        if (variance <= 1e-9) return 0.0;

        double s0 = expectedScore(elo0);
        double s1 = expectedScore(elo1);

        // Normal-approximation GSPRT: how much better does H1 explain the data than H0.
        return n * (s1 - s0) * (2 * mean - s0 - s1) / (2 * variance);
    }

    public Verdict verdict() {
        if (pairCount() < MIN_PAIRS) return Verdict.CONTINUE;
        double llr = llr();
        if (llr >= upperBound) return Verdict.H1_ACCEPTED;
        if (llr <= lowerBound) return Verdict.H0_ACCEPTED;
        return Verdict.CONTINUE;
    }

    @Override
    public String toString() {
        return String.format("games %d  score %.4f  elo %+.1f  LLR %+.2f  [%.2f, %.2f]  %s",
                gameCount(), score(), elo(), llr(), lowerBound, upperBound, verdict());
    }
}
