package strix.eval;

import strix.core.Board;

/**
 * Scores a position from the side-to-move's point of view. Positive means the
 * side to move is better, regardless of color. Units are centipawns, so 100 is
 * one pawn.
 *
 * Search depends only on this interface, so Stage 4 can swap a neural network in
 * without search knowing anything changed.
 */
public interface Evaluator {
    int evaluate(Board board);

    /**
     * What this evaluator thinks one pawn is worth.
     *
     * Search compares evaluation scores against margins expressed in material
     * centipawns, and a swapped-in evaluator does not have to agree about the
     * unit. A trained network's output scale is whatever its training objective
     * produced, and one measured here came out at 31 cp per pawn against the
     * piece-square tables' ~100.
     *
     * That is not a cosmetic difference. Delta pruning in quiescence compares
     * {@code standPat + gain + margin < alpha}, where standPat and alpha come
     * from this interface and gain and margin were material centipawns. Under a
     * compressed evaluation the right-hand side never wins, the prune stops
     * firing, and the node count at depth 4 on Kiwipete went from 6,498 to
     * 1,017,488. A 157x blowup, from a unit mismatch, with no error anywhere.
     *
     * So margins are expressed in material centipawns and converted through
     * this. 100 means "the same units search already assumed".
     */
    default int pawnValue() { return 100; }
}
