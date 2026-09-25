package strix.nnue;

import strix.core.Board;
import strix.eval.Evaluator;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The network as a drop-in {@link Evaluator}, so search never learns that its
 * evaluation stopped being a lookup table.
 *
 * <h2>Why the accumulator is not plumbed through search</h2>
 * The incremental accumulator needs a hook at every make and unmake inside the
 * search, which means the search would have to know an accumulator exists. That
 * couples search to nnue and breaks the layering the rest of the project keeps.
 *
 * The honest trade is measured rather than assumed: this evaluates from scratch,
 * which costs a full pass over the pieces per node. The accumulator exists, is
 * correct, is tested against from-scratch at every node of a tree walk, and is
 * used by {@link #evaluateIncremental}. Wiring it into search is a speed change
 * and belongs behind its own SPRT, not smuggled in alongside a change of
 * evaluation.
 *
 * Quantised integers rather than floats, for reproducibility across machines and
 * because an int accumulator cannot accumulate rounding error.
 */
public final class NnueEvaluator implements Evaluator {

    /**
     * Positions used to ask the net what a pawn is worth: each is evaluated
     * with and without one white pawn, and the median difference is the answer.
     *
     * A handful rather than one, because a single position can price a pawn
     * oddly for positional reasons, and the median rather than the mean because
     * a badly trained net produces outliers in both directions.
     */
    private static final String[] PAWN_PROBES = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 1",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 1",
        "8/5k2/4p3/3pP3/3P1K2/8/8/8 w - - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    };

    /** Removing a pawn from each probe. Same FENs with one white pawn deleted. */
    private static final String[] PAWN_PROBES_LESS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1",
        "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPP2PPP/RNBQK2R w KQkq - 0 1",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1P2QPPP/R4RK1 w - - 0 1",
        "8/5k2/4p3/3pP3/5K2/8/8/8 w - - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBB1PP/R3K2R w KQkq - 0 1",
    };

    private final Quantized net;
    private final Accumulator accumulator;
    private final int pawnValue;

    public NnueEvaluator(Network network) {
        this.net = Quantized.from(network);
        this.accumulator = new Accumulator(network);
        this.pawnValue = measurePawnValue();
    }

    /**
     * Asks the net what a pawn is worth, so search margins can be converted.
     *
     * Clamped, because this is asked of untrained and half-trained nets too. A
     * net that prices a pawn at zero or negative would otherwise invert or erase
     * every margin derived from it; one measured during development returned
     * -20. The floor keeps such a net merely bad rather than catastrophic, and
     * the material probe is the gate that should catch it long before here.
     */
    private int measurePawnValue() {
        int[] deltas = new int[PAWN_PROBES.length];
        for (int i = 0; i < PAWN_PROBES.length; i++) {
            int full = net.evaluate(strix.core.Fen.parse(PAWN_PROBES[i]));
            int less = net.evaluate(strix.core.Fen.parse(PAWN_PROBES_LESS[i]));
            deltas[i] = full - less;
        }
        java.util.Arrays.sort(deltas);
        int median = deltas[deltas.length / 2];
        return Math.max(10, Math.min(1000, median));
    }

    @Override
    public int pawnValue() { return pawnValue; }

    public static NnueEvaluator load(Path path) throws IOException {
        return new NnueEvaluator(Network.load(path));
    }

    @Override
    public int evaluate(Board board) {
        return net.evaluate(board);
    }

    /** The accumulator path, for measuring what incremental update is worth. */
    public int evaluateIncremental(Board board, boolean refresh) {
        if (refresh) accumulator.refresh(board);
        return accumulator.evaluate(board.sideToMove);
    }

    public Accumulator accumulator() { return accumulator; }
}
