package strix.nnue;

import strix.core.*;

/**
 * The incrementally maintained first layer.
 *
 * <h2>Why this exists</h2>
 * The search evaluates millions of positions per second. Recomputing the hidden
 * layer from scratch each time means touching all 32 pieces; but between one
 * position and the next, at most a handful of pieces moved. So keep the running
 * totals and adjust only what changed. That is the "efficiently updatable" in
 * NNUE.
 *
 * <p>A spreadsheet with a sum of 10,000 rows: change one cell and you subtract the
 * old value and add the new one, you do not re-add 10,000 rows.
 *
 * <h2>The invariant, which is the whole game</h2>
 * After any sequence of moves and unmoves, these accumulators must equal what
 * {@link Network#evaluate} computes from scratch. Drift here does not crash
 * anything; it silently produces a slightly wrong evaluation that gets slightly
 * wronger, and the engine plays worse for reasons that look nothing like a
 * neural network bug.
 *
 * This is the same shape as the Zobrist invariant in {@code Board}, and it is
 * tested the same way: walk the tree, compare against a fresh recomputation at
 * every node, in both directions.
 *
 * <h2>The moves that are not simple</h2>
 * Most moves are "remove a piece from here, add it there". The exceptions are
 * exactly the ones that made move generation hard:
 * <ul>
 *   <li><b>Captures</b> remove an enemy piece as well.</li>
 *   <li><b>En passant</b> removes a pawn from a square that is not the destination.</li>
 *   <li><b>Castling</b> moves two pieces.</li>
 *   <li><b>Promotion</b> changes a piece's type, so the feature that goes back is
 *       not the feature that came off.</li>
 * </ul>
 * Each is a separate branch below, and each is a separate way for the invariant
 * to break.
 */
public final class Accumulator {

    /** Indexed by perspective: [WHITE] is the board as White sees it. */
    private final float[][] acc = new float[2][Network.HIDDEN];
    private final Network network;

    public Accumulator(Network network) {
        this.network = network;
    }

    /** Rebuild from the board. Correct but slow, and the thing increments must match. */
    public void refresh(Board board) {
        for (int p = 0; p < 2; p++) {
            System.arraycopy(network.featureBias, 0, acc[p], 0, Network.HIDDEN);
        }
        for (int type = Piece.PAWN; type <= Piece.KING; type++) {
            for (int color = Piece.WHITE; color <= Piece.BLACK; color++) {
                long pieces = board.pieces(color, type);
                while (pieces != 0L) {
                    int sq = Long.numberOfTrailingZeros(pieces);
                    pieces &= pieces - 1;
                    put(color, type, sq);
                }
            }
        }
    }

    private void put(int color, int type, int sq) {
        network.addFeature(acc[Piece.WHITE], Network.featureIndex(Piece.WHITE, color, type, sq));
        network.addFeature(acc[Piece.BLACK], Network.featureIndex(Piece.BLACK, color, type, sq));
    }

    private void take(int color, int type, int sq) {
        network.removeFeature(acc[Piece.WHITE], Network.featureIndex(Piece.WHITE, color, type, sq));
        network.removeFeature(acc[Piece.BLACK], Network.featureIndex(Piece.BLACK, color, type, sq));
    }

    /**
     * Apply a move. Call this BEFORE {@code board.make(move)}, while the board
     * still shows what is being captured.
     */
    public void make(Board board, int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int flag = Move.flag(move);
        int piece = board.mailbox[from];
        int us = Piece.colorOf(piece);
        int them = Piece.other(us);
        int type = Piece.typeOf(piece);

        if (flag == Move.EP_CAPTURE) {
            int capturedSq = (us == Piece.WHITE) ? to - 8 : to + 8;
            take(them, Piece.PAWN, capturedSq);
        } else if (board.mailbox[to] != Piece.NONE) {
            int victim = board.mailbox[to];
            take(Piece.colorOf(victim), Piece.typeOf(victim), to);
        }

        take(us, type, from);
        if (Move.isPromotion(move)) {
            put(us, Move.promoPiece(move), to);     // a different piece arrives
        } else {
            put(us, type, to);
        }

        if (flag == Move.CASTLE_KING) {
            int rank = (us == Piece.WHITE) ? 0 : 56;
            take(us, Piece.ROOK, rank + 7);
            put(us, Piece.ROOK, rank + 5);
        } else if (flag == Move.CASTLE_QUEEN) {
            int rank = (us == Piece.WHITE) ? 0 : 56;
            take(us, Piece.ROOK, rank);
            put(us, Piece.ROOK, rank + 3);
        }
    }

    /** Evaluate from the current accumulators, side-to-move first. */
    public int evaluate(int sideToMove) {
        return network.output(acc[sideToMove], acc[Piece.other(sideToMove)]);
    }

    /** A copy of one perspective, for the equivalence test. */
    public float[] snapshot(int perspective) {
        return acc[perspective].clone();
    }
}
