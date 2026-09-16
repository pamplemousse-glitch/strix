package strix.eval;

import strix.core.Board;
import strix.core.Piece;

/**
 * Counts material and nothing else. Deliberately the crudest possible evaluator:
 * it is the baseline that every later evaluation term has to prove it beats, and
 * proving that needs the Stage 3 harness.
 */
public final class Material implements Evaluator {

    public static final int[] VALUE = {100, 320, 330, 500, 900, 0};

    @Override
    public int evaluate(Board board) {
        int score = 0;
        for (int type = Piece.PAWN; type <= Piece.QUEEN; type++) {
            score += VALUE[type] * Long.bitCount(board.pieces(Piece.WHITE, type));
            score -= VALUE[type] * Long.bitCount(board.pieces(Piece.BLACK, type));
        }
        return board.sideToMove == Piece.WHITE ? score : -score;
    }
}
