package strix.nnue;

import strix.core.Board;
import strix.core.Piece;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/**
 * A small NNUE: 768 inputs, one hidden layer of 256 seen from both perspectives,
 * one output.
 *
 * <h2>The input encoding</h2>
 * 768 = 6 piece types x 2 colours x 64 squares. One input per "this piece is on
 * this square", so at most 32 of the 768 are ever set. That sparsity is the whole
 * reason this architecture is fast enough to sit inside a search: the first layer
 * costs 32 column additions, not a 768x256 matrix multiply.
 *
 * <h2>Why two perspectives</h2>
 * The same position is fed in twice, once labelled from the side to move and once
 * from the opponent. Weights are shared. It costs nothing and it lets the network
 * learn "my knight here" separately from "their knight here", which is most of
 * what a chess evaluation is.
 *
 * From White's perspective a piece keeps its square; from Black's the board is
 * mirrored vertically ({@code sq ^ 56}) and the colours swap. So index 0-383 is
 * always "mine" and 384-767 is always "theirs", whoever is moving.
 *
 * <h2>Activation</h2>
 * Clipped ReLU: {@code clamp(x, 0, 1)}. Chosen over plain ReLU because the
 * quantised version in step 21 needs a bounded output to fit in an int8 without
 * overflowing, and an activation that behaves differently after quantisation
 * would defeat the point of the equivalence test.
 */
public final class Network {

    public static final int INPUTS = 768;
    public static final int HIDDEN = 256;

    /** [INPUTS][HIDDEN], stored input-major so a feature is one contiguous row. */
    final float[][] featureWeights = new float[INPUTS][HIDDEN];
    final float[] featureBias = new float[HIDDEN];

    /** [HIDDEN * 2]: side-to-move accumulator first, then the opponent's. */
    final float[] outputWeights = new float[HIDDEN * 2];
    float outputBias;

    /** Centipawns per unit of network output. */
    public static final float SCALE = 400f;

    /**
     * Feature index for a piece, as seen from {@code perspective}.
     *
     * Own pieces land in 0-383 and enemy pieces in 384-767 regardless of which
     * side is being asked, which is what makes the two perspectives share weights.
     */
    public static int featureIndex(int perspective, int pieceColor, int pieceType, int square) {
        int relativeColor = (pieceColor == perspective) ? 0 : 1;
        int relativeSquare = (perspective == Piece.WHITE) ? square : (square ^ 56);
        return relativeColor * 384 + pieceType * 64 + relativeSquare;
    }

    static float crelu(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    /**
     * Evaluate from scratch, touching every piece on the board.
     *
     * This is the reference the incremental accumulator must reproduce exactly. It
     * is never used in search once the accumulator exists, and it is kept for the
     * same reason negamax and the ray loops are kept.
     */
    public int evaluate(Board board) {
        int stm = board.sideToMove;
        float[] own = featureBias.clone();
        float[] opp = featureBias.clone();

        for (int type = Piece.PAWN; type <= Piece.KING; type++) {
            for (int color = Piece.WHITE; color <= Piece.BLACK; color++) {
                long pieces = board.pieces(color, type);
                while (pieces != 0L) {
                    int sq = Long.numberOfTrailingZeros(pieces);
                    pieces &= pieces - 1;
                    addFeature(own, featureIndex(stm, color, type, sq));
                    addFeature(opp, featureIndex(Piece.other(stm), color, type, sq));
                }
            }
        }
        return output(own, opp);
    }

    void addFeature(float[] acc, int feature) {
        float[] row = featureWeights[feature];
        for (int i = 0; i < HIDDEN; i++) acc[i] += row[i];
    }

    void removeFeature(float[] acc, int feature) {
        float[] row = featureWeights[feature];
        for (int i = 0; i < HIDDEN; i++) acc[i] -= row[i];
    }

    /** Both accumulators through the activation and the output layer. */
    public int output(float[] own, float[] opp) {
        float sum = outputBias;
        for (int i = 0; i < HIDDEN; i++) {
            sum += crelu(own[i]) * outputWeights[i];
            sum += crelu(opp[i]) * outputWeights[HIDDEN + i];
        }
        return Math.round(sum * SCALE);
    }

    // Accessors for the trainer. Deliberately narrow: the trainer may read every
    // parameter and adjust it by a delta, and may not reshape anything.

    public float[] featureBias() { return featureBias; }
    public float outputBias() { return outputBias; }
    public float outputWeight(int i) { return outputWeights[i]; }
    public float featureWeight(int f, int h) { return featureWeights[f][h]; }

    /** Add the weights of one active feature into an accumulator. */
    public void addTo(float[] acc, int feature) { addFeature(acc, feature); }

    public void adjustOutputBias(float delta) { outputBias += delta; }
    public void adjustOutputWeight(int i, float delta) { outputWeights[i] += delta; }
    public void adjustFeatureBias(int h, float delta) { featureBias[h] += delta; }
    public void adjustFeatureWeight(int f, int h, float delta) { featureWeights[f][h] += delta; }

    /** Small random weights, for testing the plumbing before any training exists. */
    public static Network random(long seed) {
        Network n = new Network();
        SplittableRandom rng = new SplittableRandom(seed);
        // Scaled so a typical position lands in a sane centipawn range rather than
        // saturating every clipped ReLU immediately.
        double scale = 1.0 / Math.sqrt(32);
        for (int f = 0; f < INPUTS; f++) {
            for (int h = 0; h < HIDDEN; h++) {
                n.featureWeights[f][h] = (float) ((rng.nextDouble() - 0.5) * 2 * scale * 0.1);
            }
        }
        for (int h = 0; h < HIDDEN; h++) n.featureBias[h] = 0f;
        for (int i = 0; i < HIDDEN * 2; i++) {
            n.outputWeights[i] = (float) ((rng.nextDouble() - 0.5) * 2 / Math.sqrt(HIDDEN * 2));
        }
        n.outputBias = 0f;
        return n;
    }

    public void save(Path path) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(INPUTS);
            out.writeInt(HIDDEN);
            for (float[] row : featureWeights) for (float w : row) out.writeFloat(w);
            for (float b : featureBias) out.writeFloat(b);
            for (float w : outputWeights) out.writeFloat(w);
            out.writeFloat(outputBias);
        }
    }

    public static Network load(Path path) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int inputs = in.readInt(), hidden = in.readInt();
            if (inputs != INPUTS || hidden != HIDDEN) {
                throw new IOException("network is " + inputs + "x" + hidden
                        + ", this build expects " + INPUTS + "x" + HIDDEN);
            }
            Network n = new Network();
            for (float[] row : n.featureWeights) {
                for (int h = 0; h < HIDDEN; h++) row[h] = in.readFloat();
            }
            for (int h = 0; h < HIDDEN; h++) n.featureBias[h] = in.readFloat();
            for (int i = 0; i < HIDDEN * 2; i++) n.outputWeights[i] = in.readFloat();
            n.outputBias = in.readFloat();
            return n;
        }
    }
}
