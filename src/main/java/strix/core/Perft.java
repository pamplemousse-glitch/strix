package strix.core;

/**
 * Node counting, the oracle for move generation correctness.
 *
 * Buffers are preallocated per ply rather than per call, because allocating a
 * move array at every one of 119 million nodes is the kind of thing that turns
 * a 30 second test into a 5 minute one.
 */
public final class Perft {

    private static final int MAX_PLY = 32;

    private Perft() {}

    private static final class Buffers {
        final int[][] legal = new int[MAX_PLY][MoveGen.MAX_MOVES];
        final int[][] scratch = new int[MAX_PLY][MoveGen.MAX_MOVES];
    }

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    public static long count(Board b, int depth) {
        return count(b, depth, 0, BUFFERS.get());
    }

    private static long count(Board b, int depth, int ply, Buffers buf) {
        if (depth == 0) return 1L;
        int[] moves = buf.legal[ply];
        int n = MoveGen.generateLegal(b, moves, buf.scratch[ply]);

        // Deliberately not short-circuiting at depth 1. Returning n directly is
        // correct and faster, but it skips make/unmake at the last ply, which is
        // exactly where undo bugs hide.
        long nodes = 0L;
        for (int i = 0; i < n; i++) {
            b.make(moves[i]);
            nodes += count(b, depth - 1, ply + 1, buf);
            b.unmake(moves[i]);
        }
        return nodes;
    }

    /**
     * Per-root-move node counts. This is the debugging tool: diff this against
     * Stockfish's `go perft N` on the same position and exactly one move will
     * disagree. Play that move and repeat one ply down.
     */
    public static String divide(Board b, int depth) {
        Buffers buf = BUFFERS.get();
        int[] moves = buf.legal[0];
        int n = MoveGen.generateLegal(b, moves, buf.scratch[0]);
        StringBuilder sb = new StringBuilder();
        long total = 0L;
        for (int i = 0; i < n; i++) {
            b.make(moves[i]);
            long sub = count(b, depth - 1, 1, buf);
            b.unmake(moves[i]);
            total += sub;
            sb.append(Move.toUci(moves[i])).append(": ").append(sub).append('\n');
        }
        sb.append("\nNodes searched: ").append(total).append('\n');
        return sb.toString();
    }
}
