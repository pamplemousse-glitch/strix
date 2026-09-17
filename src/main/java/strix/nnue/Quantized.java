package strix.nnue;

import strix.core.Board;
import strix.core.Piece;

/**
 * The same network in integers instead of floats.
 *
 * <h2>Why bother</h2>
 * Integer arithmetic is faster than floating point, it is exactly reproducible
 * across machines, and it does not accumulate rounding error over millions of
 * incremental updates. That last one matters most here: a float accumulator
 * updated a billion times drifts, and an integer one cannot.
 *
 * <h2>How the scaling works</h2>
 * Two fixed-point scales, which is the standard NNUE arrangement:
 *
 * <ul>
 *   <li>Feature weights and the accumulator are scaled by {@link #QA}. The clipped
 *       ReLU then clamps to [0, QA] rather than [0, 1].</li>
 *   <li>Output weights are scaled by {@link #QB}.</li>
 * </ul>
 *
 * A product of the two carries QA * QB, so the final sum is divided by QA * QB to
 * return to units, then multiplied by {@link Network#SCALE} for centipawns. Doing
 * it in that order, rather than dividing early, is what keeps the precision.
 *
 * <h2>Why ints are wide here</h2>
 * The accumulator is {@code int}, not {@code short}. Thirty-two features summed at
 * QA = 255 can exceed a short, and an overflow would silently wrap into a
 * plausible-looking evaluation rather than crashing. Space is not the constraint.
 *
 * <h2>The gate</h2>
 * Quantisation is lossy by construction, so unlike alpha-beta or magic bitboards
 * it cannot be held to exact equality. It is held to a tolerance instead, and the
 * float network stays in the repo as the reference that tolerance is measured
 * against.
 */
public final class Quantized {

    /** Fixed-point scale for feature weights and the accumulator. */
    public static final int QA = 255;
    /** Fixed-point scale for output weights. */
    public static final int QB = 64;

    final short[][] featureWeights = new short[Network.INPUTS][Network.HIDDEN];
    final short[] featureBias = new short[Network.HIDDEN];
    final short[] outputWeights = new short[Network.HIDDEN * 2];
    int outputBias;

    public static Quantized from(Network net) {
        Quantized q = new Quantized();
        for (int f = 0; f < Network.INPUTS; f++) {
            for (int h = 0; h < Network.HIDDEN; h++) {
                q.featureWeights[f][h] = round(net.featureWeights[f][h] * QA);
            }
        }
        for (int h = 0; h < Network.HIDDEN; h++) {
            q.featureBias[h] = round(net.featureBias[h] * QA);
        }
        for (int i = 0; i < Network.HIDDEN * 2; i++) {
            q.outputWeights[i] = round(net.outputWeights[i] * QB);
        }
        q.outputBias = Math.round(net.outputBias * QA * QB);
        return q;
    }

    private static short round(float v) {
        int r = Math.round(v);
        if (r > Short.MAX_VALUE || r < Short.MIN_VALUE) {
            throw new ArithmeticException("weight " + v + " does not fit a short after scaling; "
                    + "the network needs clipping during training");
        }
        return (short) r;
    }

    /** Clipped ReLU in fixed point: the float [0, 1] becomes [0, QA]. */
    static int crelu(int x) {
        return x < 0 ? 0 : Math.min(x, QA);
    }

    public int evaluate(Board board) {
        int stm = board.sideToMove;
        int[] own = new int[Network.HIDDEN];
        int[] opp = new int[Network.HIDDEN];
        for (int h = 0; h < Network.HIDDEN; h++) {
            own[h] = featureBias[h];
            opp[h] = featureBias[h];
        }

        for (int type = Piece.PAWN; type <= Piece.KING; type++) {
            for (int color = Piece.WHITE; color <= Piece.BLACK; color++) {
                long pieces = board.pieces(color, type);
                while (pieces != 0L) {
                    int sq = Long.numberOfTrailingZeros(pieces);
                    pieces &= pieces - 1;
                    add(own, Network.featureIndex(stm, color, type, sq));
                    add(opp, Network.featureIndex(Piece.other(stm), color, type, sq));
                }
            }
        }
        return output(own, opp);
    }

    void add(int[] acc, int feature) {
        short[] row = featureWeights[feature];
        for (int i = 0; i < Network.HIDDEN; i++) acc[i] += row[i];
    }

    void remove(int[] acc, int feature) {
        short[] row = featureWeights[feature];
        for (int i = 0; i < Network.HIDDEN; i++) acc[i] -= row[i];
    }

    public int output(int[] own, int[] opp) {
        long sum = outputBias;
        for (int i = 0; i < Network.HIDDEN; i++) {
            sum += (long) crelu(own[i]) * outputWeights[i];
            sum += (long) crelu(opp[i]) * outputWeights[Network.HIDDEN + i];
        }
        // Divide once, at the end. Dividing earlier throws away the precision the
        // fixed-point scaling exists to preserve.
        return (int) (sum * (long) Network.SCALE / ((long) QA * QB));
    }
}
