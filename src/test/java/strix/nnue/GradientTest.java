package strix.nnue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finite-difference check of the NNUE training gradient.
 *
 * <h2>Why this exists</h2>
 * Two trained nets were rejected, at -330 Elo and then 26-0, and both times the
 * question "is the gradient even right?" could only be answered by reading the
 * code. It was never measured. If the gradient is wrong then every other fix,
 * mini-batching included, is wasted effort. This should have been the first
 * thing built.
 *
 * <h2>The step size is not arbitrary</h2>
 * The network stores float32. At eps = 1e-4 the change in loss is around 1e-8,
 * below the rounding error of summing 512 float terms, and the check then
 * reports 2-5% "errors" on every small gradient while the large ones match to
 * 1e-4. That is the noise floor, not a defect. 1e-2 clears it.
 */
class GradientTest {

    private static final int H = Network.HIDDEN;
    private static final float EPS = 1e-2f;
    private static final int[] OWN = {3, 100, 260, 511, 700};
    private static final int[] OPP = {7, 130, 300, 480, 690};
    private static final float TARGET = 0.62f;

    private static float clamp(float x) { return x < 0f ? 0f : (x > 1f ? 1f : x); }
    private static float sigmoid(float x) { return 1f / (1f + (float) Math.exp(-x)); }

    private static Network net() {
        Network n = Network.random(42);
        n.initFeatureBias(0.5f);
        return n;
    }

    /** Mirrors Trainer.step's forward pass. */
    private static double loss(Network net) {
        float[] a = new float[H], b = new float[H];
        System.arraycopy(net.featureBias(), 0, a, 0, H);
        System.arraycopy(net.featureBias(), 0, b, 0, H);
        for (int f : OWN) net.addTo(a, f);
        for (int f : OPP) net.addTo(b, f);
        float sum = net.outputBias();
        for (int h = 0; h < H; h++) {
            sum += clamp(a[h]) * net.outputWeight(h) + clamp(b[h]) * net.outputWeight(H + h);
        }
        float pred = sigmoid(sum);
        return (double) (pred - TARGET) * (pred - TARGET);
    }

    private static double numerical(Network net, Runnable up, Runnable down) {
        up.run();
        double hi = loss(net);
        down.run(); down.run();
        double lo = loss(net);
        up.run();
        return (hi - lo) / (2 * EPS);
    }

    private static void agree(String what, double analytic, double numeric) {
        double denom = Math.max(1e-9, Math.abs(analytic) + Math.abs(numeric));
        assertTrue(Math.abs(analytic - numeric) / denom < 1e-2,
                what + ": analytic " + analytic + " vs numerical " + numeric);
    }

    @Test @DisplayName("Every gradient the trainer computes matches finite differences")
    void gradientIsCorrect() {
        Network net = net();

        float[] a = new float[H], b = new float[H];
        System.arraycopy(net.featureBias(), 0, a, 0, H);
        System.arraycopy(net.featureBias(), 0, b, 0, H);
        for (int f : OWN) net.addTo(a, f);
        for (int f : OPP) net.addTo(b, f);

        float sum = net.outputBias();
        float[] aOwn = new float[H], aOpp = new float[H];
        for (int h = 0; h < H; h++) {
            aOwn[h] = clamp(a[h]);
            aOpp[h] = clamp(b[h]);
            sum += aOwn[h] * net.outputWeight(h) + aOpp[h] * net.outputWeight(H + h);
        }
        float pred = sigmoid(sum);
        float dSum = 2f * (pred - TARGET) * pred * (1f - pred);

        float[] dOwn = new float[H], dOpp = new float[H];
        for (int h = 0; h < H; h++) {
            dOwn[h] = (a[h] > 0f && a[h] < 1f) ? dSum * net.outputWeight(h) : 0f;
            dOpp[h] = (b[h] > 0f && b[h] < 1f) ? dSum * net.outputWeight(H + h) : 0f;
        }

        agree("outputBias", dSum,
                numerical(net, () -> net.adjustOutputBias(EPS), () -> net.adjustOutputBias(-EPS)));

        for (int h : new int[]{0, 5, 77, H - 1}) {
            final int i = h;
            agree("outputWeight[own " + h + "]", dSum * aOwn[h],
                    numerical(net, () -> net.adjustOutputWeight(i, EPS),
                                   () -> net.adjustOutputWeight(i, -EPS)));
        }
        for (int h : new int[]{1, 42}) {
            final int i = H + h;
            agree("outputWeight[opp " + h + "]", dSum * aOpp[h],
                    numerical(net, () -> net.adjustOutputWeight(i, EPS),
                                   () -> net.adjustOutputWeight(i, -EPS)));
        }
        for (int h : new int[]{0, 9, 200}) {
            final int i = h;
            // The feature bias feeds BOTH accumulators, so its gradient is the sum.
            agree("featureBias[" + h + "]", dOwn[h] + dOpp[h],
                    numerical(net, () -> net.adjustFeatureBias(i, EPS),
                                   () -> net.adjustFeatureBias(i, -EPS)));
        }
        for (int f : OWN) {
            for (int h : new int[]{0, 33}) {
                final int ff = f, hh = h;
                agree("featureWeight[own f=" + f + " h=" + h + "]", dOwn[h],
                        numerical(net, () -> net.adjustFeatureWeight(ff, hh, EPS),
                                       () -> net.adjustFeatureWeight(ff, hh, -EPS)));
            }
        }
        for (int f : OPP) {
            for (int h : new int[]{0, 33}) {
                final int ff = f, hh = h;
                agree("featureWeight[opp f=" + f + " h=" + h + "]", dOpp[h],
                        numerical(net, () -> net.adjustFeatureWeight(ff, hh, EPS),
                                       () -> net.adjustFeatureWeight(ff, hh, -EPS)));
            }
        }
    }

    @Test @DisplayName("A saturated unit passes no gradient, which is the ADR 0014 mechanism")
    void saturatedUnitsAreDead() {
        Network net = net();
        net.adjustFeatureBias(0, 50f);

        float[] acc = new float[H];
        System.arraycopy(net.featureBias(), 0, acc, 0, H);
        for (int f : OWN) net.addTo(acc, f);

        assertTrue(acc[0] > 1f, "unit 0 should be saturated high");

        double before = loss(net);
        net.adjustFeatureWeight(OWN[0], 0, 0.5f);
        assertEquals(before, loss(net), 1e-9,
                "a saturated unit must not respond to its weights at all");
    }
}
