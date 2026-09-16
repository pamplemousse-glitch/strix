package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.harness.Sprt;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate for step 17: feed the test synthetic results from a player of KNOWN
 * strength and check it reaches the right verdict at the right rate.
 *
 * Porting statistics you did not derive is exactly the situation that needs an
 * external check, because a wrong formula still produces confident-looking
 * numbers.
 */
class SprtTest {

    /** Simulate a pair between players separated by a known Elo gap. */
    private static int simulatePair(RandomGenerator rng, double elo, double drawRate) {
        int score = 0;
        for (int game = 0; game < 2; game++) {
            double expected = 1.0 / (1.0 + Math.pow(10.0, -elo / 400.0));
            // Split the non-draw probability around the expected score.
            double win = expected - drawRate / 2.0;
            double r = rng.nextDouble();
            if (r < win) score += 2;                     // win, 2 half-points
            else if (r < win + drawRate) score += 1;     // draw, 1 half-point
        }
        return score;   // 0..4
    }

    private static Sprt.Verdict run(long seed, double trueElo, double drawRate, int maxPairs) {
        var rng = new java.util.SplittableRandom(seed);
        Sprt sprt = Sprt.standard();
        for (int i = 0; i < maxPairs; i++) {
            sprt.record(simulatePair(rng, trueElo, drawRate));
            Sprt.Verdict v = sprt.verdict();
            if (v != Sprt.Verdict.CONTINUE) return v;
        }
        return Sprt.Verdict.CONTINUE;
    }

    @Test @DisplayName("A clearly stronger patch is accepted")
    void acceptsRealGains() {
        int accepted = 0;
        for (int seed = 0; seed < 20; seed++) {
            if (run(seed, 40.0, 0.30, 20_000) == Sprt.Verdict.H1_ACCEPTED) accepted++;
        }
        assertTrue(accepted >= 18, "a +40 Elo patch was accepted only " + accepted + "/20 times");
    }

    @Test @DisplayName("A clearly worse patch is rejected")
    void rejectsRegressions() {
        int rejected = 0;
        for (int seed = 0; seed < 20; seed++) {
            if (run(seed, -40.0, 0.30, 20_000) == Sprt.Verdict.H0_ACCEPTED) rejected++;
        }
        assertTrue(rejected >= 18, "a -40 Elo patch was rejected only " + rejected + "/20 times");
    }

    @Test @DisplayName("A worthless patch is rejected, not accepted")
    void rejectsNoise() {
        int accepted = 0, rejected = 0;
        for (int seed = 100; seed < 140; seed++) {
            Sprt.Verdict v = run(seed, 0.0, 0.30, 40_000);
            if (v == Sprt.Verdict.H1_ACCEPTED) accepted++;
            if (v == Sprt.Verdict.H0_ACCEPTED) rejected++;
        }
        // alpha is 0.05, so a handful of false accepts is expected and correct.
        assertTrue(rejected > accepted,
                "0 Elo patch: " + accepted + " accepted vs " + rejected + " rejected");
        assertTrue(accepted <= 6, "too many false accepts: " + accepted + "/40, alpha is 0.05");
    }

    @Test @DisplayName("Stops far sooner than a fixed game count")
    void stopsEarly() {
        var rng = new java.util.SplittableRandom(7);
        Sprt sprt = Sprt.standard();
        while (sprt.verdict() == Sprt.Verdict.CONTINUE && sprt.pairCount() < 50_000) {
            sprt.record(simulatePair(rng, 60.0, 0.30));
        }
        assertEquals(Sprt.Verdict.H1_ACCEPTED, sprt.verdict());
        System.out.println("  +60 Elo settled after " + sprt.gameCount() + " games: " + sprt);
        assertTrue(sprt.gameCount() < 4_000,
                "took " + sprt.gameCount() + " games to detect +60 Elo");
    }

    @Test @DisplayName("Bounds are the textbook values")
    void bounds() {
        Sprt s = Sprt.standard();
        assertEquals(Math.log(19), s.upperBound, 1e-9);
        assertEquals(-Math.log(19), s.lowerBound, 1e-9);
    }
}
