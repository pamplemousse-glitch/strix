package strix.search;

import strix.core.Piece;

/** What the GUI told us about how long we may think. */
public final class SearchLimits {

    public long wtime = -1, btime = -1, winc = 0, binc = 0;
    public long movetime = -1;
    public int depth = 64;
    public boolean infinite;

    /**
     * Time to allocate for this move, in milliseconds.
     *
     * remaining/20 + increment/2 is the standard starting formula. It is
     * deliberately crude: allocating more time in complex positions needs the
     * Stage 3 harness to prove it helps, and an unmeasured time-management
     * heuristic is as likely to lose Elo as gain it.
     */
    public long allocate(int sideToMove) {
        if (infinite) return Long.MAX_VALUE;
        if (movetime > 0) return movetime;

        long remaining = (sideToMove == Piece.WHITE) ? wtime : btime;
        long inc = (sideToMove == Piece.WHITE) ? winc : binc;
        if (remaining < 0) return Long.MAX_VALUE;

        long budget = remaining / 20 + inc / 2;
        // Never burn the whole clock on one move, and always leave a safety margin.
        return Math.max(1, Math.min(budget, remaining - 50));
    }
}
