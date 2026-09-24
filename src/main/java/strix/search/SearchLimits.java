package strix.search;

import strix.core.Piece;

/** What the GUI told us about how long we may think. */
public final class SearchLimits {

    public long wtime = -1, btime = -1, winc = 0, binc = 0;
    public long movetime = -1;
    public long nodes = -1;
    public int depth = 64;
    public boolean infinite;

    /**
     * Nodes per millisecond. When set, the clock is spent in NODES rather than
     * milliseconds: the budget is allocated exactly as it would be on a real
     * clock, then converted at this rate.
     *
     * This is Stockfish's `nodestime`, and it exists because wall-clock testing
     * measures your machine's background load as much as your engine. The same
     * match run twice gives different games if something else was using the CPU.
     *
     * The tradeoff is real: nodestime cannot see a change in nodes per second, so
     * a pure speed optimisation registers as nothing. Use a real time control to
     * catch those. See ADR 0011.
     */
    public long nodestime = 0;

    /**
     * Reserved for everything between deciding a move and the server seeing it.
     *
     * 100 ms was the old figure and it only accounted for the engine. Over the
     * network the server has been counting since before the gameState event was
     * sent and keeps counting through the move POST, which is itself retried up
     * to three times. Every millisecond of that comes out of our clock.
     */
    private static final long MOVE_OVERHEAD_MILLIS = 300;

    /** Used when no clock is known at all. Enough to be useful, small enough to be safe. */
    private static final long DEFAULT_BUDGET_MILLIS = 1_000;

    /**
     * Time to allocate for this move, in milliseconds.
     *
     * remaining/20 + increment/2 is the standard starting formula. It is
     * deliberately crude: allocating more time in complex positions needs the
     * Stage 3 harness to prove it helps, and an unmeasured time-management
     * heuristic is as likely to lose Elo as gain it.
     */
    /** Node budget for this move, or -1 to use the clock instead. */
    public long allocateNodes(int sideToMove) {
        if (nodes > 0) return nodes;
        if (nodestime > 0) {
            long ms = allocate(sideToMove);
            if (ms == Long.MAX_VALUE) return Long.MAX_VALUE;
            return Math.max(1, ms * nodestime);
        }
        return -1;
    }

    public long allocate(int sideToMove) {
        if (infinite) return Long.MAX_VALUE;
        if (movetime > 0) return movetime;

        long remaining = (sideToMove == Piece.WHITE) ? wtime : btime;
        long inc = (sideToMove == Piece.WHITE) ? winc : binc;

        // A missing clock for the side to move used to return Long.MAX_VALUE,
        // i.e. search to depth 63 and never answer. "go btime 60000 binc 2000"
        // with white to move does it. Borrow the opponent's clock if there is
        // one, since in every real time control they start equal, and otherwise
        // take a conservative fixed slice. Moving on a guess beats not moving.
        if (remaining < 0) {
            long other = (sideToMove == Piece.WHITE) ? btime : wtime;
            if (other < 0) return DEFAULT_BUDGET_MILLIS;
            remaining = other;
            if (inc <= 0) inc = (sideToMove == Piece.WHITE) ? binc : winc;
        }

        // remaining/20 was too greedy for blitz: at 5+3 with 280s left it allocated
        // 14 seconds and then spent all of them, including on forced recaptures.
        // A game is 40 moves more often than 20.
        long budget = remaining / 30 + inc / 2;
        // Never burn the whole clock on one move, and always leave a safety margin.
        return Math.max(1, Math.min(budget, remaining - MOVE_OVERHEAD_MILLIS));
    }
}
