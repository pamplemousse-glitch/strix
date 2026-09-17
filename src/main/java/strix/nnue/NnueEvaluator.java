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

    private final Quantized net;
    private final Accumulator accumulator;

    public NnueEvaluator(Network network) {
        this.net = Quantized.from(network);
        this.accumulator = new Accumulator(network);
    }

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
