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
 * The exact GSPRT, in {@link Gsprt}. An earlier version used the normal
 * approximation and had to be abandoned: it divided by an observed variance that
 * is zero when early pairs all score alike, which produced an LLR of -3.6e9 after
 * two pairs. See docs/adr/0010.
 *
 * Verified in SprtTest by feeding synthetic results from a player of known strength
 * and checking both the verdict and the rate at which it is reached. That test is
 * what caught the -3.6e9 bug, and it is what validates this port.
 */
public final class Sprt {

    public enum Verdict { CONTINUE, H1_ACCEPTED, H0_ACCEPTED }

    /**
     * A small floor, kept for a different reason than before.
     *
     * The old normal approximation needed a guard of 16 because it divided by an
     * observed variance that could be zero. The exact GSPRT has no such failure
     * mode, so this is now only a sanity floor: with a single pair there is no
     * distribution shape to fit at all.
     */
    public static final int MIN_PAIRS = 2;

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

    /**
     * @param pairScore 0, 1, 2, 3 or 4, meaning 0 to 2 points in half-point steps.
     *
     * Checked rather than trusted. This is a bare array index reached from two
     * places that parse untrusted text: a resumed log file, and an HTTP body
     * posted by a worker that may be running a different build. An out-of-range
     * value threw an AIOOBE that unwound through the coordinator, after the key
     * had already been marked counted, so the observation was lost AND the run
     * could never resume past that line.
     */
    public void record(int pairScore) {
        if (pairScore < 0 || pairScore >= pairs.length) {
            throw new IllegalArgumentException(
                    "pair score must be 0..4, got " + pairScore);
        }
        pairs[pairScore]++;
    }

    public long pairCount() {
        long n = 0;
        for (long p : pairs) n += p;
        return n;
    }

    public long gameCount() { return pairCount() * 2; }

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
        return Gsprt.eloOf(score());
    }

    public double llr() {
        if (pairCount() < MIN_PAIRS) return 0.0;
        return Gsprt.llr(pairs, Gsprt.expectedScore(elo0), Gsprt.expectedScore(elo1));
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
