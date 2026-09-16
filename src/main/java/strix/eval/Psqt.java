package strix.eval;

import strix.core.Board;
import strix.core.Piece;

/**
 * Material plus piece-square tables.
 *
 * Material alone cannot distinguish between any two quiet moves, so a
 * material-only engine plays whatever move happened to be generated first.
 * These tables give every square a small bonus or penalty per piece type, which
 * is enough to produce recognisable chess: knights come to the centre, pawns
 * advance, the king hides in the corner.
 *
 * Values are the widely used "simplified evaluation function" tables from the
 * Chess Programming Wiki (Tomasz Michniewski). They are a starting point, not a
 * tuned result. Texel tuning in step 18b replaces them with fitted values, and
 * Stage 4 replaces the whole thing with a network.
 *
 * Tables are written with rank 8 on the first row, so a white piece indexes at
 * {@code sq ^ 56} and a black piece at {@code sq}, which mirrors vertically.
 */
public final class Psqt implements Evaluator {

    private static final int[] PAWN = {
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
         5,  5, 10, 25, 25, 10,  5,  5,
         0,  0,  0, 20, 20,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0
    };
    private static final int[] KNIGHT = {
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 10, 15, 15, 10,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 10, 15, 15, 10,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    };
    private static final int[] BISHOP = {
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5, 10, 10,  5,  0,-10,
        -10,  5,  5, 10, 10,  5,  5,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10, 10, 10, 10, 10, 10, 10,-10,
        -10,  5,  0,  0,  0,  0,  5,-10,
        -20,-10,-10,-10,-10,-10,-10,-20
    };
    private static final int[] ROOK = {
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
         0,  0,  0,  5,  5,  0,  0,  0
    };
    private static final int[] QUEEN = {
        -20,-10,-10, -5, -5,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5,  5,  5,  5,  0,-10,
         -5,  0,  5,  5,  5,  5,  0, -5,
          0,  0,  5,  5,  5,  5,  0, -5,
        -10,  5,  5,  5,  5,  5,  0,-10,
        -10,  0,  5,  0,  0,  0,  0,-10,
        -20,-10,-10, -5, -5,-10,-10,-20
    };
    private static final int[] KING_MG = {
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -20,-30,-30,-40,-40,-30,-30,-20,
        -10,-20,-20,-20,-20,-20,-20,-10,
         20, 20,  0,  0,  0,  0, 20, 20,
         20, 30, 10,  0,  0, 10, 30, 20
    };
    private static final int[] KING_EG = {
        -50,-40,-30,-20,-20,-30,-40,-50,
        -30,-20,-10,  0,  0,-10,-20,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-30,  0,  0,  0,  0,-30,-30,
        -50,-30,-30,-30,-30,-30,-30,-50
    };

    private static final int[][] TABLES = {PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING_MG};

    /** Phase weights. Queens and rooks dominate whether it is still a middlegame. */
    private static final int[] PHASE_WEIGHT = {0, 1, 1, 2, 4, 0};
    private static final int TOTAL_PHASE = 24;

    @Override
    public int evaluate(Board board) {
        int score = 0;
        int phase = 0;

        for (int type = Piece.PAWN; type <= Piece.KING; type++) {
            for (int color = Piece.WHITE; color <= Piece.BLACK; color++) {
                long pieces = board.pieces(color, type);
                int sign = (color == Piece.WHITE) ? 1 : -1;
                while (pieces != 0L) {
                    int sq = Long.numberOfTrailingZeros(pieces);
                    pieces &= pieces - 1;
                    int index = (color == Piece.WHITE) ? (sq ^ 56) : sq;

                    score += sign * Material.VALUE[type];
                    phase += PHASE_WEIGHT[type];

                    if (type == Piece.KING) {
                        // Interpolated below, once the phase is known.
                        continue;
                    }
                    score += sign * TABLES[type][index];
                }
            }
        }

        score += kingScore(board, Piece.WHITE, phase);
        score -= kingScore(board, Piece.BLACK, phase);

        return board.sideToMove == Piece.WHITE ? score : -score;
    }

    /**
     * The king wants opposite things at opposite ends of the game: safety behind
     * pawns early, activity in the centre late. Interpolating between the two
     * tables by remaining material is the cheapest version of tapered eval.
     */
    private static int kingScore(Board board, int color, int phase) {
        int sq = board.kingSquare(color);
        int index = (color == Piece.WHITE) ? (sq ^ 56) : sq;
        int clamped = Math.min(phase, TOTAL_PHASE);
        return (KING_MG[index] * clamped + KING_EG[index] * (TOTAL_PHASE - clamped)) / TOTAL_PHASE;
    }
}
