package strix.search;

import strix.core.Board;
import strix.core.Move;
import strix.core.Piece;
import strix.eval.Material;

/**
 * Move ordering. This is what makes alpha-beta actually work.
 *
 * Alpha-beta only cuts off when it finds a refutation, so the whole benefit
 * depends on trying good moves first. With perfect ordering the searched tree is
 * roughly the square root of the full tree, which is twice the depth in the same
 * time. With random ordering most of that is lost.
 *
 * Priority, highest first:
 *   1. The transposition table's move. It was best here before.
 *   2. Captures, by MVV-LVA: take the most valuable victim with the least
 *      valuable attacker. Pawn takes queen before queen takes pawn.
 *   3. Killer moves: a quiet move that caused a cutoff in a sibling position
 *      very often causes one here too.
 *   4. History: quiet moves that keep causing cutoffs anywhere get tried earlier
 *      everywhere.
 */
public final class Ordering {

    private static final int TT_MOVE = 1 << 24;
    private static final int CAPTURE_BASE = 1 << 20;
    private static final int KILLER_1 = (1 << 19) + 1;
    private static final int KILLER_2 = 1 << 19;

    private final int[][] killers = new int[64][2];
    private final int[][] history = new int[12][64];

    public void clear() {
        for (int[] k : killers) java.util.Arrays.fill(k, Move.NONE);
        for (int[] h : history) java.util.Arrays.fill(h, 0);
    }

    public void onCutoff(Board board, int move, int depth, int ply) {
        if (Move.isCapture(move)) return;        // captures are ordered well already
        if (killers[ply][0] != move) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = move;
        }
        int piece = board.mailbox[Move.from(move)];
        if (piece != Piece.NONE) history[piece][Move.to(move)] += depth * depth;
    }

    private int score(Board board, int move, int ttMove, int ply) {
        if (move == ttMove) return TT_MOVE;

        if (Move.isCapture(move)) {
            int victim = board.mailbox[Move.to(move)];
            int attacker = board.mailbox[Move.from(move)];
            int victimValue = (victim == Piece.NONE)
                    ? Material.VALUE[Piece.PAWN]                       // en passant
                    : Material.VALUE[Piece.typeOf(victim)];
            int attackerValue = (attacker == Piece.NONE) ? 0 : Material.VALUE[Piece.typeOf(attacker)];
            return CAPTURE_BASE + victimValue * 16 - attackerValue;
        }

        if (move == killers[ply][0]) return KILLER_1;
        if (move == killers[ply][1]) return KILLER_2;

        int piece = board.mailbox[Move.from(move)];
        return piece == Piece.NONE ? 0 : history[piece][Move.to(move)];
    }

    /**
     * Selection sort, one move at a time, rather than sorting the whole list.
     * Most nodes cut off after a few moves, so sorting the tail is wasted work.
     */
    public void pickBest(Board board, int[] moves, int n, int index, int ttMove, int ply) {
        int bestIndex = index;
        int bestScore = score(board, moves[index], ttMove, ply);
        for (int i = index + 1; i < n; i++) {
            int s = score(board, moves[i], ttMove, ply);
            if (s > bestScore) {
                bestScore = s;
                bestIndex = i;
            }
        }
        int tmp = moves[index];
        moves[index] = moves[bestIndex];
        moves[bestIndex] = tmp;
    }
}
