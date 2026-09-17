package strix.core;


/**
 * Zobrist hashing: one random 64-bit number per (piece, square), XORed together.
 *
 * XOR is its own inverse, so moving a piece is two XORs rather than rehashing the
 * board. That is what makes the hash cheap enough to maintain on every make and
 * unmake, which is what makes a transposition table possible.
 *
 * Keys come from a fixed seed so hashes are reproducible across runs. That
 * matters for debugging: the same position always has the same key.
 */
public final class Zobrist {

    public static final long[][] PIECE = new long[12][64];
    public static final long[] CASTLING = new long[16];
    public static final long[] EP_FILE = new long[8];
    public static final long SIDE;

    static {
        var rng = new java.util.SplittableRandom(0x5721A9C3E1B7D4F1L);
        for (int p = 0; p < 12; p++) {
            for (int sq = 0; sq < 64; sq++) PIECE[p][sq] = rng.nextLong();
        }
        for (int i = 0; i < 16; i++) CASTLING[i] = rng.nextLong();
        for (int i = 0; i < 8; i++) EP_FILE[i] = rng.nextLong();
        SIDE = rng.nextLong();
    }

    private Zobrist() {}

    /** Recompute from scratch. The oracle the incremental hash is checked against. */
    public static long compute(Board b) {
        long key = 0L;
        for (int sq = 0; sq < 64; sq++) {
            int p = b.mailbox[sq];
            if (p != Piece.NONE) key ^= PIECE[p][sq];
        }
        key ^= CASTLING[b.castling];
        if (b.epSquare != Square.NONE) key ^= EP_FILE[Square.file(b.epSquare)];
        if (b.sideToMove == Piece.BLACK) key ^= SIDE;
        return key;
    }
}
