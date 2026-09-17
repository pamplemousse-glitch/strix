package strix.search;

import strix.core.Move;

/**
 * A cache of positions already searched, keyed by Zobrist hash.
 *
 * The same position is reached by many different move orders, so without this
 * the search re-does enormous amounts of work. Entries also supply the previously
 * best move, which feeds move ordering, and that turns out to be worth more than
 * the cutoffs themselves (Rustic measured +42 Elo from cuts and +100 from the
 * move ordering the table enables).
 *
 * Only the low bits of the key index the table, so two positions can collide.
 * The full key is stored and compared, which makes a wrong hit require a full
 * 64-bit collision. At any realistic table size that is rare enough to tolerate,
 * and the engine is written so that a bad entry costs accuracy rather than
 * legality: the stored move is always re-validated by the move generator.
 */
public final class TranspositionTable {

    public static final int EXACT = 0;   // score is exact
    public static final int LOWER = 1;   // score is at least this (a beta cutoff)
    public static final int UPPER = 2;   // score is at most this (never raised alpha)

    public static final int MISS = Integer.MIN_VALUE;

    private final long[] keys;
    private final long[] data;
    private final int mask;

    public long hits, probes, stores;

    public TranspositionTable(int megabytes) {
        int entries = Integer.highestOneBit(Math.max(1, megabytes * 1024 * 1024 / 16));
        keys = new long[entries];
        data = new long[entries];
        mask = entries - 1;
    }

    public void clear() {
        java.util.Arrays.fill(keys, 0L);
        java.util.Arrays.fill(data, 0L);
        hits = probes = stores = 0;
    }

    private static long pack(int move, int score, int depth, int flag) {
        return (move & 0xFFFFL)
                | ((score & 0xFFFFL) << 16)
                | ((depth & 0xFFL) << 32)
                | ((long) (flag & 0x3) << 40);
    }

    public static int moveOf(long d)  { return (int) (d & 0xFFFF); }
    private static int scoreOf(long d) { return (short) ((d >>> 16) & 0xFFFF); }
    private static int depthOf(long d) { return (int) ((d >>> 32) & 0xFF); }
    private static int flagOf(long d)  { return (int) ((d >>> 40) & 0x3); }

    /** The stored move for this position, or Move.NONE. Always re-validated by the caller. */
    public int probeMove(long key) {
        int i = (int) (key & mask);
        return keys[i] == key ? moveOf(data[i]) : Move.NONE;
    }

    /**
     * A usable score, or MISS.
     *
     * Mate scores are relative to the ply they were found at, so they are stored
     * absolute and converted back on the way out. Skipping that makes the engine
     * announce mate in 3 forever without ever delivering it.
     */
    public int probe(long key, int depth, int alpha, int beta, int ply) {
        probes++;
        int i = (int) (key & mask);
        if (keys[i] != key) return MISS;

        long d = data[i];
        if (depthOf(d) < depth) return MISS;

        int score = scoreOf(d);
        if (score > Search.MATE - 100) score -= ply;
        else if (score < -Search.MATE + 100) score += ply;

        int flag = flagOf(d);
        if (flag == EXACT
                || (flag == LOWER && score >= beta)
                || (flag == UPPER && score <= alpha)) {
            hits++;
            return score;
        }
        return MISS;
    }

    public void store(long key, int depth, int score, int flag, int move, int ply) {
        int i = (int) (key & mask);
        // Depth-preferred: a deeper result is more valuable than a fresher one,
        // except when the slot holds a different position entirely.
        if (keys[i] == key && depthOf(data[i]) > depth) return;

        if (score > Search.MATE - 100) score += ply;
        else if (score < -Search.MATE + 100) score -= ply;

        keys[i] = key;
        data[i] = pack(move, score, depth, flag);
        stores++;
    }
}
