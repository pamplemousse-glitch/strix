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
}
