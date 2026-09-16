package strix.core;

/**
 * Attack tables.
 *
 * Non-sliding pieces (knight, king, pawn) are precomputed once into lookup
 * tables. Sliding pieces walk rays at query time, which is slow and deliberate:
 * this is the reference implementation that magic bitboards must reproduce
 * byte-for-byte in step 7b. See docs/build-plan.md.
 *
 * Rays are walked by file/rank deltas rather than raw square offsets so that a
 * piece on the h-file cannot wrap around onto the a-file.
 */
public final class Attacks {

    public static final long[] KNIGHT = new long[64];
    public static final long[] KING = new long[64];
    public static final long[][] PAWN = new long[2][64];

    private static final int[][] KNIGHT_DELTAS = {
            {1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}
    };
    private static final int[][] KING_DELTAS = {
            {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
    };
    private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, -1}, {-1, 1}};
    private static final int[][] ROOK_DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    static {
        for (int sq = 0; sq < 64; sq++) {
            int f = Square.file(sq), r = Square.rank(sq);
            KNIGHT[sq] = stepsFrom(f, r, KNIGHT_DELTAS);
            KING[sq] = stepsFrom(f, r, KING_DELTAS);
            PAWN[Piece.WHITE][sq] = stepsFrom(f, r, new int[][]{{-1, 1}, {1, 1}});
            PAWN[Piece.BLACK][sq] = stepsFrom(f, r, new int[][]{{-1, -1}, {1, -1}});
        }
    }

    private Attacks() {}

    private static long stepsFrom(int f, int r, int[][] deltas) {
        long bb = 0L;
        for (int[] d : deltas) {
            int ff = f + d[0], rr = r + d[1];
            if (ff >= 0 && ff < 8 && rr >= 0 && rr < 8) {
                bb |= 1L << Square.of(ff, rr);
            }
        }
        return bb;
    }

    private static long ray(int sq, long occupied, int[][] dirs) {
        long attacks = 0L;
        int f = Square.file(sq), r = Square.rank(sq);
        for (int[] d : dirs) {
            int ff = f + d[0], rr = r + d[1];
            while (ff >= 0 && ff < 8 && rr >= 0 && rr < 8) {
                int s = Square.of(ff, rr);
                attacks |= 1L << s;
                if ((occupied & (1L << s)) != 0L) break;   // blocker: include it, stop
                ff += d[0];
                rr += d[1];
            }
        }
        return attacks;
    }

    public static long bishop(int sq, long occupied) { return ray(sq, occupied, BISHOP_DIRS); }
    public static long rook(int sq, long occupied)   { return ray(sq, occupied, ROOK_DIRS); }
    public static long queen(int sq, long occupied)  {
        return bishop(sq, occupied) | rook(sq, occupied);
    }
}
