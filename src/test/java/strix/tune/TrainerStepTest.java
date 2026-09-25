package strix.tune;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.Board;
import strix.core.Fen;
import strix.nnue.Network;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the update {@link Trainer#step} actually applies, not a copy of the
 * maths written next to it.
 *
 * <h2>Why the existing gradient test was not enough</h2>
 * {@code strix.nnue.GradientTest} re-derives the gradient inside the test and
 * finite-differences that. It verifies the algebra on paper; {@code step} could
 * be deleted and it would still pass. It also uses hand-picked own/opp index
 * sets that are DISJOINT, and real positions are not:
 *
 * <pre>
 *   startpos                      32 own features, 32 shared with opp (100%)
 *   Kiwipete                      32 own features, 12 shared          ( 38%)
 *   a pawn endgame                10 own features,  0 shared          (  0%)
 * </pre>
 *
 * A feature present in both sets has one true gradient, {@code dOwn + dOpp}.
 * Applying two separate optimiser steps to it is not the same thing, and the
 * disjoint test cannot see the difference.
 *
 * <h2>Why the first optimiser step is exactly -LR * sign(gradient)</h2>
 * At t = 1 Adam's bias correction is exact: mHat = g and vHat = g*g, so the step
 * is {@code -LR * g / (|g| + eps)}, independent of the gradient's magnitude.
 * Every parameter with a non-negligible gradient must therefore move by exactly
 * LR. That makes the assertion sharp and needs no access to the gradient arrays.
 */
class TrainerStepTest {

    private static final float LR = Float.parseFloat(System.getProperty("strix.lr", "0.0001"));

    private static Network fresh() {
        Network n = Network.random(2024);
        n.initFeatureBias(0.5f);
        return n;
    }

    private static Trainer.Sample sampleOf(String fen, int cp) {
        Board b = Fen.parse(fen);
        return Trainer.toSample(b, cp);
    }

    /**
     * Every parameter Adam touched on its first step must have moved by exactly
     * the learning rate. A doubly-applied update moves by roughly 2x, and can
     * move the wrong way entirely when the two halves nearly cancel.
     */
    private static void assertFirstStepIsExactlyLr(String fen, int cp) {
        Network before = fresh();
        Network after = fresh();
        Trainer.step(after, new Trainer.Adam(), sampleOf(fen, cp));

        Trainer.Sample s = sampleOf(fen, cp);
        int wrong = 0, checked = 0;
        StringBuilder detail = new StringBuilder();

        for (int f : s.own()) {
            for (int h = 0; h < Network.HIDDEN; h += 37) {
                float delta = weight(after, f, h) - weight(before, f, h);
                if (Math.abs(delta) < 1e-9f) continue;   // gradient was zero
                checked++;
                double ratio = Math.abs(delta) / LR;
                if (Math.abs(ratio - 1.0) > 0.02) {
                    wrong++;
                    if (detail.length() < 400) {
                        detail.append(String.format("%n  f=%d h=%d moved %.3fx LR", f, h, ratio));
                    }
                }
            }
        }
        assertTrue(checked > 0, "no feature weight moved at all on " + fen);
        assertEquals(0, wrong,
                "on " + fen + ": " + wrong + " of " + checked
                        + " feature weights moved by the wrong amount." + detail);
    }

    private static float weight(Network net, int f, int h) {
        // Read a single feature weight by writing zero and observing nothing:
        // Network exposes adjust but not a getter, so compare via accumulators.
        float[] acc = new float[Network.HIDDEN];
        net.addTo(acc, f);
        return acc[h];
    }

    @Test @DisplayName("Symmetric position: every own feature is also an opp feature")
    void symmetricPosition() {
        // 100% overlap. This is the worst case and the most common shape early
        // in a game, which is most of any Lichess dataset.
        assertFirstStepIsExactlyLr("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 20);
    }

    @Test @DisplayName("Partially overlapping position")
    void partialOverlap() {
        assertFirstStepIsExactlyLr(
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", -35);
    }

    @Test @DisplayName("Disjoint position, which is the only case the old test covered")
    void noOverlap() {
        assertFirstStepIsExactlyLr("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 150);
    }
}
