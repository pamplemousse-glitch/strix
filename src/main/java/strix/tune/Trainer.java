package strix.tune;

import strix.core.*;
import strix.nnue.Network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Trains the NNUE by gradient descent. No external toolchain: the network, the
 * inference and the training all live in this repo and this language.
 *
 * <h2>The target</h2>
 * Stockfish's centipawn evaluation of the position, squashed into a win
 * probability by a sigmoid. Training on raw centipawns would let a single +900
 * position dominate the gradient; in probability space the difference between
 * +900 and +1200 is correctly almost nothing.
 *
 * <h2>Why this is fast enough in plain Java</h2>
 * The first layer is sparse. At most 32 of 768 inputs are set, so both the
 * forward pass and the gradient touch 32 rows of 256 rather than a 768x256 matrix.
 * That is roughly 8,000 operations per example instead of 200,000.
 *
 * <h2>Adam rather than plain SGD</h2>
 * Per-parameter adaptive step sizes matter here because feature weights are
 * updated at wildly different rates: a pawn on e4 appears in most positions, a
 * king on h3 in almost none. Plain SGD with one learning rate either crawls on the
 * common features or diverges on the rare ones.
 *
 * <h2>The holdout</h2>
 * A fifth of the data is never trained on, for the reason ADR 0013 records: a
 * falling training error is not a better evaluation. Unlike the Texel run, the
 * split here can be random, because each position carries its own independent
 * Stockfish label rather than a result shared with 32 of its neighbours.
 */
public final class Trainer {

    private static final float LR = 0.01f;
    private static final float BETA1 = 0.9f, BETA2 = 0.999f, EPS = 1e-8f;

    /** Sparse: the active feature indices for each perspective, plus the target. */
    private record Sample(int[] own, int[] opp, float target) {}

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args.length > 0 ? args[0] : "runs/labelled.txt");
        Path out = Path.of(args.length > 1 ? args[1] : "runs/net.bin");
        int epochs = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        List<Sample> all = load(data);
        System.out.printf("loaded %,d samples%n", all.size());

        SplittableRandom rng = new SplittableRandom(0x5721A9C3L);
        java.util.Collections.shuffle(all, new java.util.Random(12345));
        int holdout = all.size() / 5;
        List<Sample> validate = all.subList(0, holdout);
        List<Sample> train = all.subList(holdout, all.size());
        System.out.printf("train %,d   validate %,d%n%n", train.size(), validate.size());

        Network net = Network.random(0xC0FFEE);
        Adam adam = new Adam();

        double bestValidation = Double.MAX_VALUE;
        Network best = null;
        int worseStreak = 0;

        for (int epoch = 1; epoch <= epochs; epoch++) {
            java.util.Collections.shuffle(train, new java.util.Random(rng.nextLong()));
            double loss = 0;
            long t0 = System.currentTimeMillis();

            for (Sample s : train) loss += step(net, adam, s);
            loss /= train.size();

            double validation = 0;
            for (Sample s : validate) validation += squaredError(net, s);
            validation /= validate.size();

            long secs = (System.currentTimeMillis() - t0) / 1000;
            boolean better = validation < bestValidation;
            System.out.printf("epoch %2d  train %.6f  validate %.6f %s (%ds)%n",
                    epoch, loss, validation, better ? "" : "  <- worse", secs);

            if (better) {
                bestValidation = validation;
                best = copy(net);
                worseStreak = 0;
            } else if (++worseStreak >= 3) {
                System.out.println("\nheld-out loss rising for 3 epochs: stopping");
                break;
            }
        }

        if (best == null) best = net;
        best.save(out);
        System.out.printf("%nbest validation loss: %.6f%n", bestValidation);
        System.out.println("wrote " + out);
        System.out.println("\nNOT VALIDATED. A lower loss is not a stronger engine.");
        System.out.println("Run the SPRT harness before believing it. See ADR 0013.");
    }

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    /** Forward, backward, update. Returns the squared error for this sample. */
    private static double step(Network net, Adam adam, Sample s) {
        float[] own = new float[Network.HIDDEN];
        float[] opp = new float[Network.HIDDEN];
        System.arraycopy(net.featureBias(), 0, own, 0, Network.HIDDEN);
        System.arraycopy(net.featureBias(), 0, opp, 0, Network.HIDDEN);
        for (int f : s.own()) net.addTo(own, f);
        for (int f : s.opp()) net.addTo(opp, f);

        float sum = net.outputBias();
        float[] aOwn = new float[Network.HIDDEN];
        float[] aOpp = new float[Network.HIDDEN];
        for (int h = 0; h < Network.HIDDEN; h++) {
            aOwn[h] = clamp(own[h]);
            aOpp[h] = clamp(opp[h]);
            sum += aOwn[h] * net.outputWeight(h);
            sum += aOpp[h] * net.outputWeight(Network.HIDDEN + h);
        }

        float pred = sigmoid(sum);
        float diff = pred - s.target();
        // d(loss)/d(sum) for loss = (pred - target)^2 with pred = sigmoid(sum)
        float dSum = 2f * diff * pred * (1f - pred);

        adam.outputBias(net, dSum);
        float[] dOwn = new float[Network.HIDDEN];
        float[] dOpp = new float[Network.HIDDEN];
        for (int h = 0; h < Network.HIDDEN; h++) {
            adam.outputWeight(net, h, dSum * aOwn[h]);
            adam.outputWeight(net, Network.HIDDEN + h, dSum * aOpp[h]);
            // Clipped ReLU passes no gradient outside [0, 1].
            dOwn[h] = (own[h] > 0f && own[h] < 1f) ? dSum * net.outputWeight(h) : 0f;
            dOpp[h] = (opp[h] > 0f && opp[h] < 1f) ? dSum * net.outputWeight(Network.HIDDEN + h) : 0f;
        }

        for (int h = 0; h < Network.HIDDEN; h++) adam.featureBias(net, h, dOwn[h] + dOpp[h]);
        for (int f : s.own()) adam.featureRow(net, f, dOwn);
        for (int f : s.opp()) adam.featureRow(net, f, dOpp);

        return diff * diff;
    }

    private static double squaredError(Network net, Sample s) {
        float[] own = new float[Network.HIDDEN];
        float[] opp = new float[Network.HIDDEN];
        System.arraycopy(net.featureBias(), 0, own, 0, Network.HIDDEN);
        System.arraycopy(net.featureBias(), 0, opp, 0, Network.HIDDEN);
        for (int f : s.own()) net.addTo(own, f);
        for (int f : s.opp()) net.addTo(opp, f);

        float sum = net.outputBias();
        for (int h = 0; h < Network.HIDDEN; h++) {
            sum += clamp(own[h]) * net.outputWeight(h);
            sum += clamp(opp[h]) * net.outputWeight(Network.HIDDEN + h);
        }
        float d = sigmoid(sum) - s.target();
        return d * d;
    }

    private static float clamp(float x) { return x < 0f ? 0f : (x > 1f ? 1f : x); }

    private static Network copy(Network n) throws IOException {
        Path tmp = Files.createTempFile("strix-net", ".bin");
        n.save(tmp);
        Network c = Network.load(tmp);
        Files.deleteIfExists(tmp);
        return c;
    }

    /** Adam, one moment pair per parameter. */
    private static final class Adam {
        private final float[][] mFeature = new float[Network.INPUTS][Network.HIDDEN];
        private final float[][] vFeature = new float[Network.INPUTS][Network.HIDDEN];
        private final float[] mBias = new float[Network.HIDDEN];
        private final float[] vBias = new float[Network.HIDDEN];
        private final float[] mOut = new float[Network.HIDDEN * 2];
        private final float[] vOut = new float[Network.HIDDEN * 2];
        private float mOutBias, vOutBias;
        private long t;

        private float update(float grad, float[] m, float[] v, int i) {
            m[i] = BETA1 * m[i] + (1 - BETA1) * grad;
            v[i] = BETA2 * v[i] + (1 - BETA2) * grad * grad;
            float mHat = m[i] / (1 - (float) Math.pow(BETA1, t));
            float vHat = v[i] / (1 - (float) Math.pow(BETA2, t));
            return LR * mHat / ((float) Math.sqrt(vHat) + EPS);
        }

        void outputBias(Network net, float grad) {
            t++;
            mOutBias = BETA1 * mOutBias + (1 - BETA1) * grad;
            vOutBias = BETA2 * vOutBias + (1 - BETA2) * grad * grad;
            float mHat = mOutBias / (1 - (float) Math.pow(BETA1, t));
            float vHat = vOutBias / (1 - (float) Math.pow(BETA2, t));
            net.adjustOutputBias(-LR * mHat / ((float) Math.sqrt(vHat) + EPS));
        }

        void outputWeight(Network net, int i, float grad) {
            net.adjustOutputWeight(i, -update(grad, mOut, vOut, i));
        }

        void featureBias(Network net, int h, float grad) {
            net.adjustFeatureBias(h, -update(grad, mBias, vBias, h));
        }

        void featureRow(Network net, int f, float[] grads) {
            float[] m = mFeature[f], v = vFeature[f];
            for (int h = 0; h < Network.HIDDEN; h++) {
                if (grads[h] == 0f) continue;
                net.adjustFeatureWeight(f, h, -update(grads[h], m, v, h));
            }
        }
    }

    private static List<Sample> load(Path path) throws IOException {
        List<Sample> out = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int bar = line.lastIndexOf('|');
            if (bar < 0) continue;
            try {
                Board b = Fen.parse(line.substring(0, bar).trim());
                int cp = Integer.parseInt(line.substring(bar + 1).trim());
                out.add(toSample(b, cp));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static Sample toSample(Board b, int cp) {
        int stm = b.sideToMove;
        int[] own = new int[32], opp = new int[32];
        int n = 0;
        for (int type = Piece.PAWN; type <= Piece.KING; type++) {
            for (int color = Piece.WHITE; color <= Piece.BLACK; color++) {
                long pieces = b.pieces(color, type);
                while (pieces != 0L) {
                    int sq = Long.numberOfTrailingZeros(pieces);
                    pieces &= pieces - 1;
                    own[n] = Network.featureIndex(stm, color, type, sq);
                    opp[n] = Network.featureIndex(Piece.other(stm), color, type, sq);
                    n++;
                }
            }
        }
        int[] o = java.util.Arrays.copyOf(own, n);
        int[] p = java.util.Arrays.copyOf(opp, n);
        // Centipawns into a win probability, on the same SCALE the engine uses.
        float target = 1f / (1f + (float) Math.exp(-cp / Network.SCALE));
        return new Sample(o, p, target);
    }
}
