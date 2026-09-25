package strix.tune;

import strix.core.*;
import strix.eval.Material;
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
 * A fifth of the GAMES are never trained on, for the reason ADR 0013 records: a
 * falling training error is not a better evaluation.
 *
 * An earlier version split randomly by position and argued that was safe, because
 * each position carries its own independent Stockfish label rather than a result
 * shared with 32 of its neighbours. The labels are indeed independent. The
 * positions are not. Two positions from one game are a couple of moves apart and
 * share a pawn structure and a piece set, so a random split drops near-duplicates
 * of the training data into the validation set.
 *
 * The holdout was therefore measuring "can it evaluate positions it has almost
 * already seen", passed easily, and said nothing about the engine. Measured:
 * 0.0086 held-out loss, the lowest ever recorded here, at -249 Elo. See ADR 0014.
 */
public final class Trainer {

    /**
     * Independent samples per parameter, below which training is refused.
     *
     * 10x is the conventional floor and 100x is comfortable. The first NNUE run
     * was at 0.8x. See ADR 0014.
     */
    private static final int MIN_RATIO = 10;

    /**
     * Learning rate, overridable for experiments via -Dstrix.lr=...
     *
     * This trainer updates once per SAMPLE. Reference NNUE trainers update once
     * per batch of 16,384, so at the same nominal rate this takes four orders of
     * magnitude more steps per epoch and the weights slam into their clip bounds
     * inside the first epoch.
     *
     * Measured on 300k positions, two epochs each:
     *
     *   LR       dead units   pre-activation     saturated   validate
     *   0.003    211/256      [-2.60, 3.24]      99.5%       0.0737
     *   0.0001     0/256      [-0.90,  0.66]     78.5%       0.0720
     *   0.00001    0/256      [ 0.20,  0.73]      0.0%       0.0731
     *
     * 0.003 is the old default and it is what killed the net in ADR 0014: the
     * gradient through a saturated clipped ReLU is exactly zero, so a unit that
     * leaves the window never returns. That ADR blamed data volume. Data volume
     * was also a problem, but this is what killed the units, and no amount of
     * data would have fixed it.
     *
     * 0.00001 keeps every unit comfortably inside the window and learns too
     * slowly to be worth it. 0.0001 is the measured middle.
     */
    private static final float LR = Float.parseFloat(System.getProperty("strix.lr", "0.0001"));
    private static final float BETA1 = 0.9f, BETA2 = 0.999f, EPS = 1e-8f;

    /**
     * Feature weights are clipped to this after every update, and it is the single
     * most important number in this file.
     *
     * The clipped ReLU passes gradient only on (0, 1). Outside that window the
     * derivative is exactly zero, so a unit that leaves can never come back. At
     * most 32 features are active at once, so if weights are free to grow the
     * accumulator spreads far wider than the activation window and the units die.
     *
     * Measured on the first trained network: accumulator range [-15.31, 9.38],
     * 254 of 256 units dead, 1 live. That is not a 256-neuron network, it is a
     * 2-neuron one, and it lost to piece-square tables by 249 Elo.
     *
     * Sizing it: with 32 active features of mixed sign the sum scales as
     * sqrt(32) * w, about 5.7w. Starting from a bias of 0.5, a swing of +/- 0.5
     * fills the window, so w is about 0.09.
     *
     * 0.02 was tried first and was four times too tight: units stayed alive but the
     * held-out loss was four times worse, because the network could not express
     * anything. See ADR 0014.
     */
    private static final float FEATURE_CLIP = 0.09f;

    /** Sparse: the active feature indices for each perspective, plus the target. */
    record Sample(int[] own, int[] opp, float target) {}

    public static void main(String[] args) throws Exception {
        // Flags are scanned out first so they can appear anywhere, which matters
        // because the positional arguments already have defaults and people reach
        // for the flag without supplying the ones before it.
        boolean force = false, wantCurve = false;
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            switch (a) {
                case "--force" -> force = true;
                case "--curve" -> wantCurve = true;
                default -> pos.add(a);
            }
        }

        Path data = Path.of(pos.size() > 0 ? pos.get(0) : "runs/labelled.txt");
        Path out = Path.of(pos.size() > 1 ? pos.get(1) : "runs/net.bin");
        int epochs = pos.size() > 2 ? Integer.parseInt(pos.get(2)) : 30;

        List<Tagged> all = load(data);
        System.out.printf("loaded %,d samples%n", all.size());
        checkLabelSign(data);

        Split split = splitByGame(all);

        // Independent samples are games, not positions. Checked before any
        // training happens, because this is the number that decided the outcome
        // of both previous attempts and it costs nothing to look at.
        checkRatio(split, force);
        System.out.println();

        if (wantCurve) {
            curve(split, epochs);
            return;
        }

        Result r = trainOnce(split.train(), split.validate(), epochs, true);
        r.net().save(out);
        System.out.printf("%nbest validation loss: %.6f%n", r.loss());
        System.out.println("wrote " + out);
        System.out.println("\nNOT VALIDATED. A lower loss is not a stronger engine.");
        System.out.println("Run the SPRT harness before believing it. See ADR 0013.");
    }

    /** The best network found and the held-out loss that made it best. */
    private record Result(Network net, double loss) {}

    /**
     * One training run to early stopping.
     *
     * Quiet mode exists for the scaling curve, which runs this four times and
     * wants four numbers rather than four screens of epochs.
     */
    private static Result trainOnce(List<Sample> train, List<Sample> validate,
                                    int epochs, boolean verbose) throws IOException {
        SplittableRandom rng = new SplittableRandom(0x5721A9C3L);
        List<Sample> shuffled = new ArrayList<>(train);

        Network net = Network.random(0xC0FFEE);
        // Start the units inside the activation window rather than at its edge, so
        // they have somewhere to move before the gradient vanishes.
        net.initFeatureBias(0.5f);
        Adam adam = new Adam();

        double bestValidation = Double.MAX_VALUE;
        Network best = null;
        int worseStreak = 0;

        for (int epoch = 1; epoch <= epochs; epoch++) {
            java.util.Collections.shuffle(shuffled, new java.util.Random(rng.nextLong()));
            double loss = 0;
            long t0 = System.currentTimeMillis();

            for (Sample s : shuffled) loss += step(net, adam, s);
            loss /= shuffled.size();

            double validation = 0;
            for (Sample s : validate) validation += squaredError(net, s);
            validation /= validate.size();

            long secs = (System.currentTimeMillis() - t0) / 1000;
            boolean better = validation < bestValidation;
            // Live units are the health metric that actually matters here. A
            // falling loss with a dying network is the failure this whole run
            // exists to avoid repeating.
            if (verbose) {
                System.out.printf("epoch %2d  train %.6f  validate %.6f %s (%ds)%n",
                        epoch, loss, validation, better ? "" : "  <- worse", secs);
                System.out.printf("          %s%n", health(net, validate, 2000));
            }

            if (better) {
                bestValidation = validation;
                best = copy(net);
                worseStreak = 0;
            } else if (++worseStreak >= 3) {
                if (verbose) System.out.println("\nheld-out loss rising for 3 epochs: stopping");
                break;
            }
        }

        return new Result(best == null ? net : best, bestValidation);
    }

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    /** Forward, backward, update. Returns the squared error for this sample. */
    static double step(Network net, Adam adam, Sample s) {
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

        // Backprop reads the FORWARD-pass output weights, so every gradient is
        // computed before any of them is touched. Interleaving the two let the
        // hidden-layer gradient read a weight Adam had already moved, which
        // adds a constant -LR*|dSum| to all 256 units. Harmless at 1e-4 and
        // sign-flipping for 6 of 24 units at 0.02, i.e. it becomes a first-order
        // bug the moment the rate is raised.
        float[] dOwn = new float[Network.HIDDEN];
        float[] dOpp = new float[Network.HIDDEN];
        for (int h = 0; h < Network.HIDDEN; h++) {
            // Clipped ReLU passes no gradient outside [0, 1].
            dOwn[h] = (own[h] > 0f && own[h] < 1f) ? dSum * net.outputWeight(h) : 0f;
            dOpp[h] = (opp[h] > 0f && opp[h] < 1f) ? dSum * net.outputWeight(Network.HIDDEN + h) : 0f;
        }

        adam.outputBias(net, dSum);
        for (int h = 0; h < Network.HIDDEN; h++) {
            adam.outputWeight(net, h, dSum * aOwn[h]);
            adam.outputWeight(net, Network.HIDDEN + h, dSum * aOpp[h]);
        }
        for (int h = 0; h < Network.HIDDEN; h++) adam.featureBias(net, h, dOwn[h] + dOpp[h]);

        applyFeatureRows(net, adam, s, dOwn, dOpp);
        return diff * diff;
    }

    /**
     * One optimiser step per feature row, even when a feature is in both
     * perspectives.
     *
     * The own and opp index sets overlap, and heavily: every mirrored same-type
     * pair (Ra1/Ra8, a2/a7, castled kings) lands on one index in one set and the
     * other index in the other. Measured on real positions, the starting
     * position shares ALL 32 of its features, Kiwipete shares 12 of 32, and a
     * pawn endgame shares none.
     *
     * Such a feature has one true gradient, dOwn + dOpp. Calling the optimiser
     * twice is not the same thing, because Adam normalises each half separately:
     * the applied step came out between 0.19x and 2.34x the intended size, and
     * where the halves nearly cancel, which is exactly what a well-trained net
     * looks like, the sign of what is left is arbitrary. Measured on the
     * starting position: 224 of 224 sampled weights moved by the wrong amount.
     *
     * The features that overlap are the kings, rooks and pawns. The material
     * features.
     */
    private static void applyFeatureRows(Network net, Adam adam, Sample s,
                                         float[] dOwn, float[] dOpp) {
        int[] own = s.own(), opp = s.opp();
        boolean[] oppHandled = new boolean[opp.length];
        float[] combined = null;

        for (int f : own) {
            int j = indexOf(opp, f);
            if (j < 0) {
                adam.featureRow(net, f, dOwn);
                continue;
            }
            oppHandled[j] = true;
            if (combined == null) combined = new float[Network.HIDDEN];
            for (int h = 0; h < Network.HIDDEN; h++) combined[h] = dOwn[h] + dOpp[h];
            adam.featureRow(net, f, combined);
        }
        for (int j = 0; j < opp.length; j++) {
            if (!oppHandled[j]) adam.featureRow(net, opp[j], dOpp);
        }
    }

    /** Linear scan; both arrays hold at most 32 entries. */
    private static int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) if (array[i] == value) return i;
        return -1;
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

    /** Hidden units strictly inside (0, 1), and therefore still able to learn. */
    private static int liveUnits(Network net, Sample s) {
        float[] own = new float[Network.HIDDEN];
        System.arraycopy(net.featureBias(), 0, own, 0, Network.HIDDEN);
        for (int f : s.own()) net.addTo(own, f);
        int live = 0;
        for (float v : own) if (v > 0f && v < 1f) live++;
        return live;
    }

    /**
     * Pre-activation health across many positions.
     *
     * @param dead units whose pre-activation never once landed strictly inside
     *             the clipped-ReLU window across the whole sample
     * @param min  lowest pre-activation seen anywhere
     * @param max  highest
     * @param mean average
     * @param saturatedFraction share of all (unit, position) pairs outside [0,1]
     */
    record Health(int dead, float min, float max, float mean, double saturatedFraction) {
        @Override public String toString() {
            return String.format("dead %d/%d  pre-act [%.2f, %.2f] mean %.2f  saturated %.1f%%",
                    dead, Network.HIDDEN, min, max, mean, saturatedFraction * 100);
        }
    }

    /**
     * The measurement that would have ended the first NNUE attempt in minutes.
     *
     * ADR 0014 records a net where 254 of 256 units were dead because the
     * accumulator spanned [-15.31, 9.38] against a clipped-ReLU window of
     * [0, 1], and the gradient through a saturated clipped ReLU is exactly zero,
     * so a unit that leaves can never return. That was discovered after
     * training, after quantization, and after several hundred games of chess.
     *
     * A single position is not enough to see it: liveUnits above samples one,
     * and one position can saturate for reasons that say nothing about the net.
     * This walks a few thousand and reports the distribution.
     */
    private static Health health(Network net, List<Sample> samples, int limit) {
        int n = Math.min(limit, samples.size());
        if (n == 0) return new Health(0, 0, 0, 0, 0);

        boolean[] everLive = new boolean[Network.HIDDEN];
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        double sum = 0, saturated = 0, count = 0;
        float[] acc = new float[Network.HIDDEN];

        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            System.arraycopy(net.featureBias(), 0, acc, 0, Network.HIDDEN);
            for (int f : s.own()) net.addTo(acc, f);

            for (int h = 0; h < Network.HIDDEN; h++) {
                float v = acc[h];
                if (v > 0f && v < 1f) everLive[h] = true;
                else saturated++;
                if (v < min) min = v;
                if (v > max) max = v;
                sum += v;
                count++;
            }
        }
        int dead = 0;
        for (boolean live : everLive) if (!live) dead++;
        return new Health(dead, min, max, (float) (sum / count), saturated / count);
    }

    private static Network copy(Network n) throws IOException {
        Path tmp = Files.createTempFile("strix-net", ".bin");
        n.save(tmp);
        Network c = Network.load(tmp);
        Files.deleteIfExists(tmp);
        return c;
    }

    /** Adam, one moment pair per parameter. */
    static final class Adam {
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
            // Clipped for the same reason the weights are, and it was missing.
            // A little headroom past the window so a unit sitting at the edge
            // can still be pushed back in by its weights.
            net.clipFeatureBias(h, -0.25f, 1.25f);
        }

        void featureRow(Network net, int f, float[] grads) {
            float[] m = mFeature[f], v = vFeature[f];
            for (int h = 0; h < Network.HIDDEN; h++) {
                if (grads[h] == 0f) continue;
                net.adjustFeatureWeight(f, h, -update(grads[h], m, v, h));
                net.clipFeatureWeight(f, h, FEATURE_CLIP);
            }
        }
    }

    /** A sample and the game it came from, so the holdout can keep games whole. */
    private record Tagged(Sample sample, int gameId) {}

    /**
     * Streamed, not slurped.
     *
     * readAllLines held every line as a String alongside the samples built from
     * them. At the 5.6M-row scale real training data arrives in, that is roughly
     * a gigabyte of char arrays kept alive for no reason, on top of the samples
     * themselves. Each line is parsed and discarded now.
     */
    private static List<Tagged> load(Path path) throws IOException {
        List<Tagged> out = new ArrayList<>();
        int inCheck = 0, unparseable = 0;

        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Dataset.Row row = Dataset.parse(line);
                if (row == null) continue;
                try {
                    Board b = Fen.parse(row.fen());

                    // A position with the side to move in check is not quiet: its
                    // value is decided by a forced sequence the static evaluation
                    // cannot see, so it teaches the net an association that does
                    // not hold. Stockfish's own data loader drops these, and so
                    // does bullet's binpack filter.
                    if (b.inCheck(b.sideToMove)) { inCheck++; continue; }

                    int cp = Integer.parseInt(row.label());
                    out.add(new Tagged(toSample(b, cp), row.gameId()));
                } catch (Exception e) {
                    unparseable++;
                }
            }
        }
        if (inCheck > 0 || unparseable > 0) {
            System.out.printf("skipped %,d in check, %,d unparseable%n", inCheck, unparseable);
        }
        return out;
    }

    /**
     * Refuses a dataset whose labels are not side-to-move relative.
     *
     * This is the check that would have saved the second NNUE attempt.
     *
     * The network is side-to-move relative: {@link Network#featureIndex} maps
     * own pieces to 0-383 and enemy pieces to 384-767 with the board mirrored,
     * so its input is IDENTICAL for a position and its colour-flipped twin.
     * Nothing in the input says who is White.
     *
     * Lichess publishes centipawns WHITE-relative. Train on those directly and
     * the expected target for a given own-advantage a is
     * {@code 0.5*sigmoid(a) + 0.5*(1-sigmoid(a)) = 0.5} exactly: material is not
     * hard to learn, it is analytically cancelled. Measured on the offending
     * file, the correlation between white material and the label was +0.70 for
     * BOTH sides to move, where a correct file gives +0.88 and -0.88.
     *
     * The resulting net reached a validation loss 6% better than predicting a
     * constant, could not tell a queen up from a queen down, and lost 26-0.
     * See ADR 0021.
     *
     * Material is the cheapest probe available: any sane evaluation correlates
     * strongly with it, so a weak or negative correlation means the labels are
     * not describing the position the network is being shown.
     */
    private static void checkLabelSign(Path data) throws IOException {
        Material material = new Material();
        List<double[]> white = new ArrayList<>(), black = new ArrayList<>();

        try (var reader = Files.newBufferedReader(data, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null
                    && white.size() + black.size() < 40_000) {
                Dataset.Row row = Dataset.parse(line);
                if (row == null) continue;
                try {
                    Board b = Fen.parse(row.fen());
                    double[] pair = {material.evaluate(b), Integer.parseInt(row.label())};
                    (b.sideToMove == Piece.WHITE ? white : black).add(pair);
                } catch (Exception ignored) { }
            }
        }
        if (white.size() < 100 || black.size() < 100) return;

        // Split by side to move rather than pooled, because pooling hides the
        // exact failure this exists to catch. The broken dataset scored +0.725
        // on white-to-move and -0.710 on black-to-move: each frame is strongly
        // correlated, one of them is inverted, and pooled they cancel to +0.03.
        // A file that was half-converted, or skewed toward one side, could pool
        // above any threshold while one frame is still backwards.
        double rw = correlation(white), rb = correlation(black);
        System.out.printf("label check: corr(side-to-move material, label) = %+.3f white, %+.3f black%n",
                rw, rb);

        if (rw < 0.3 || rb < 0.3) {
            System.out.printf("%nREFUSING TO TRAIN.%n");
            System.out.printf("Labels do not describe the position the network is shown.%n");
            System.out.printf("A correct dataset gives roughly +0.7 to +0.9 on BOTH sides.%n%n");
            System.out.printf("One frame strongly negative means WHITE-relative labels. This%n");
            System.out.printf("network is side-to-move relative and cannot see which side is%n");
            System.out.printf("White, so white-relative labels cancel material exactly. Negate%n");
            System.out.printf("the score when the side to move is black. See ADR 0021.%n");
            System.exit(2);
        }
    }

    private static double correlation(List<double[]> pairs) {
        int n = pairs.size();
        double mx = 0, my = 0;
        for (double[] v : pairs) { mx += v[0]; my += v[1]; }
        mx /= n; my /= n;
        double num = 0, dx = 0, dy = 0;
        for (double[] v : pairs) {
            num += (v[0] - mx) * (v[1] - my);
            dx += (v[0] - mx) * (v[0] - mx);
            dy += (v[1] - my) * (v[1] - my);
        }
        return (dx > 0 && dy > 0) ? num / Math.sqrt(dx * dy) : 0;
    }

    /**
     * Holds out a fifth of the GAMES, not a fifth of the positions.
     *
     * A random split by position was the original mistake and it is a quiet one:
     * two positions from the same game are a couple of moves apart, so a held-out
     * position sits right next to a trained-on one. The holdout then measures
     * "can it evaluate positions it has almost already seen", which is a far
     * easier question than the one it is supposed to answer, and it reports a
     * healthy number while the engine is getting worse. Measured: 0.0086 held-out
     * loss, the best ever recorded here, at -249 Elo. See ADR 0014.
     */
    /** Training set kept as whole games, plus the flat holdout. */
    record Split(List<List<Sample>> trainGames, List<Sample> validate, int untagged) {
        List<Sample> train() {
            List<Sample> flat = new ArrayList<>();
            for (List<Sample> g : trainGames) flat.addAll(g);
            return flat;
        }
    }

    private static Split splitByGame(List<Tagged> all) {
        java.util.Map<Integer, List<Sample>> byGame = new java.util.LinkedHashMap<>();
        int untagged = 0;
        for (Tagged t : all) {
            if (t.gameId() == Dataset.NO_GAME) untagged++;
            // Untagged rows get a unique key so they behave like the old
            // per-position split rather than collapsing into one giant "game".
            int key = t.gameId() == Dataset.NO_GAME ? -(byGame.size() + 2) : t.gameId();
            byGame.computeIfAbsent(key, k -> new ArrayList<>()).add(t.sample());
        }

        List<List<Sample>> games = new ArrayList<>(byGame.values());
        java.util.Collections.shuffle(games, new java.util.Random(12345));

        List<List<Sample>> trainGames = new ArrayList<>();
        List<Sample> validate = new ArrayList<>();
        int target = all.size() / 5;
        int positions = 0;
        for (List<Sample> game : games) {
            if (validate.size() < target) validate.addAll(game);
            else { trainGames.add(game); positions += game.size(); }
        }
        System.out.printf("%,d games -> train %,d positions in %,d games   validate %,d%n",
                games.size(), positions, trainGames.size(), validate.size());
        return new Split(trainGames, validate, untagged);
    }

    /**
     * Refuses to train when there is less evidence than there are things to learn.
     *
     * Independent samples are GAMES, not positions. 198,014 positions drawn from
     * 6,000 games is about 6,000 independent samples, because 33 positions from
     * one game share a pawn structure and a piece set.
     *
     * The first NNUE run had 153,372 samples against 197,000 parameters, a ratio
     * of 0.8: fewer examples than unknowns. That was computable before a line of
     * training code ran, and nothing about the day spent on activation functions
     * afterwards could have changed it. See ADR 0014.
     *
     * 10x is the floor and 100x is comfortable. Real NNUE training sits in that
     * range, on hundreds of millions of positions.
     */
    private static void checkRatio(Split split, boolean force) {
        int params = Network.parameterCount();

        // An untagged row gets its own synthetic key above, so it counts as a
        // one-position "game". Feeding that count to the ratio check turns a
        // count of POSITIONS into a claimed count of independent samples, and
        // overstates independence by about 33x: exactly the error this guardrail
        // exists to catch. If any row lacks a game id, independence is unknown
        // and the honest answer is to refuse rather than to guess.
        if (split.untagged() > 0) {
            System.out.printf("%n%,d rows carry no game id, so independent samples cannot be%n",
                    split.untagged());
            System.out.printf("counted. Positions from one game are not independent: 198,014%n");
            System.out.printf("positions from 6,000 games is about 6,000 samples, not 198,014.%n%n");
            System.out.printf("Regenerate with SelfPlay, which writes the id. See Dataset.%n");
            if (!force) {
                System.out.printf("%nREFUSING TO TRAIN. Pass --force to train anyway.%n");
                System.exit(2);
            }
            System.out.printf("%nForced, and the ratio below is meaningless.%n");
        }

        // target = all.size()/5 is 0 for fewer than five loadable rows, which
        // leaves validate empty: `validation /= validate.size()` is NaN and
        // `validate.get(0)` in the live-unit count throws. Reachable by pointing
        // Trainer at positions.txt, whose 1.0/0.5 labels all fail parseInt and
        // are dropped one by one in silence.
        if (split.validate().isEmpty() || split.trainGames().isEmpty()) {
            System.err.printf("%nnot enough usable rows: %,d training games, %,d holdout positions.%n",
                    split.trainGames().size(), split.validate().size());
            System.err.println("Is this the right file? Trainer wants Stockfish centipawns");
            System.err.println("(runs/labelled.txt), not the 1.0/0.5/0.0 results in positions.txt.");
            System.exit(2);
        }

        int independentSamples = split.trainGames().size();
        double ratio = independentSamples / (double) params;
        // Small ratios are the interesting ones and %.2f rounds them all to
        // "0.00x", which hides the difference between 0.8 and 0.0004.
        String shown = ratio >= 1 ? String.format("%.1fx", ratio)
                     : ratio >= 0.01 ? String.format("%.2fx", ratio)
                     : String.format("%.5fx", ratio);
        System.out.printf("%,d independent samples / %,d parameters = %s%n",
                independentSamples, params, shown);

        if (ratio >= MIN_RATIO) return;

        System.out.printf("%n%s%n", force ? "BELOW THE FLOOR, forced." : "REFUSING TO TRAIN.");
        System.out.printf("%s is below the %dx floor, so this network has more%n", shown, MIN_RATIO);
        System.out.printf("parameters than evidence. It will fit the training set and mean%n");
        System.out.printf("nothing, which is what happened at 0.8x: held-out loss 0.0086, the%n");
        System.out.printf("lowest ever recorded here, at -249 Elo.%n%n");
        System.out.printf("Need about %,d independent games for this architecture. Options:%n",
                (long) MIN_RATIO * params);
        System.out.printf("  - more games (Lichess open database, not self-play)%n");
        System.out.printf("  - fewer parameters (Network.HIDDEN is currently %d)%n%n", Network.HIDDEN);

        if (!force) {
            System.out.printf("Pass --force to train anyway.%n");
            System.exit(2);
        }
    }

    /**
     * Held-out loss against how much data produced it.
     *
     * This is the diagnostic the first two runs were missing. A curve still
     * falling at 100% says more data helps, and the slope says roughly how much.
     * A flat curve says data is NOT the constraint and something else is wrong.
     * Every single-number metric this project trusted conflated those two cases.
     *
     * Subsamples GAMES, so each point is honestly less evidence rather than the
     * same games sliced thinner.
     */
    private static void curve(Split split, int epochs) throws IOException {
        double[] fractions = {0.1, 0.3, 0.6, 1.0};
        System.out.printf("%-10s %-10s %-12s %s%n", "fraction", "games", "positions", "held-out loss");

        for (double f : fractions) {
            int n = Math.max(1, (int) Math.round(split.trainGames().size() * f));
            List<Sample> train = new ArrayList<>();
            for (int i = 0; i < n; i++) train.addAll(split.trainGames().get(i));
            if (train.isEmpty()) continue;

            double loss = trainOnce(train, split.validate(), epochs, false).loss();
            System.out.printf("%-10s %-10d %-12d %.6f%n",
                    String.format("%.0f%%", f * 100), n, train.size(), loss);
        }

        System.out.printf("%nFalling at 100%%: more data helps, and the slope says how much.%n");
        System.out.printf("Flat: data is not the constraint, and more of it will not help.%n");
        System.out.printf("Either way this is a proxy. Only SPRT decides. See ADR 0013.%n");
    }

    static Sample toSample(Board b, int cp) {
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
