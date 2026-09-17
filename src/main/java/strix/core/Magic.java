package strix.core;

import java.util.SplittableRandom;

/**
 * Magic bitboards: sliding-piece attacks by table lookup instead of a ray walk.
 *
 * <h2>The idea</h2>
 * A rook's attacks depend only on the blockers along its own rank and file. Mask
 * the board down to those squares, and there are at most 4096 distinct patterns
 * per square. Small enough to precompute every answer.
 *
 * The problem is turning a blocker pattern into a table index quickly. The trick
 * is a multiply and a shift:
 *
 * <pre>    index = (blockers * magic) >>> (64 - bits)</pre>
 *
 * For the right magic, every distinct pattern lands in a distinct slot. There is
 * no formula for that number: you try random ones until one works. Hence "magic".
 *
 * <h2>Why the edges are excluded from the mask</h2>
 * A blocker on the far edge changes nothing, because there is nothing beyond it to
 * block. Dropping edge squares from the mask cuts a rook from 14 relevant bits to
 * 12, which is the difference between a 16384-entry table and a 4096-entry one.
 *
 * <h2>Java notes</h2>
 * The multiply relies on 64-bit overflow wrapping, which Java's signed {@code long}
 * does correctly for the low 64 bits. The shift must be {@code >>>}: an arithmetic
 * {@code >>} sign-extends and produces a negative index.
 *
 * C++ engines have a third option here, the {@code PEXT} instruction, which does
 * this extraction in hardware. Java exposes no intrinsic for it, so magics are the
 * ceiling. See ADR 0002.
 *
 * <h2>This must agree with Attacks</h2>
 * The ray-loop implementation in {@link Attacks} is kept as the reference. These
 * tables are BUILT from it, and the perft suite then proves the two produce
 * identical results. An optimisation that changes the answer is a bug.
 */
public final class Magic {

    private static final long[] ROOK_MAGIC = new long[64];
    private static final long[] BISHOP_MAGIC = new long[64];
    private static final long[] ROOK_MASK = new long[64];
    private static final long[] BISHOP_MASK = new long[64];
    private static final int[] ROOK_SHIFT = new int[64];
    private static final int[] BISHOP_SHIFT = new int[64];
    private static final long[][] ROOK_ATTACKS = new long[64][];
    private static final long[][] BISHOP_ATTACKS = new long[64][];

    private static final int[][] ROOK_DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
    private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, -1}, {-1, 1}};

    static {
        for (int sq = 0; sq < 64; sq++) {
            ROOK_MASK[sq] = relevantMask(sq, ROOK_DIRS);
            BISHOP_MASK[sq] = relevantMask(sq, BISHOP_DIRS);
            ROOK_SHIFT[sq] = 64 - Long.bitCount(ROOK_MASK[sq]);
            BISHOP_SHIFT[sq] = 64 - Long.bitCount(BISHOP_MASK[sq]);
        }
        // Fixed seed: the same magics every run, so a failure is reproducible.
        SplittableRandom rng = new SplittableRandom(0x5721A9C3E1B7D4F1L);
        for (int sq = 0; sq < 64; sq++) {
            ROOK_ATTACKS[sq] = build(sq, ROOK_MASK[sq], ROOK_SHIFT[sq], ROOK_DIRS, rng, ROOK_MAGIC);
            BISHOP_ATTACKS[sq] = build(sq, BISHOP_MASK[sq], BISHOP_SHIFT[sq], BISHOP_DIRS, rng, BISHOP_MAGIC);
        }
    }

    private Magic() {}

    public static long rook(int sq, long occupied) {
        long blockers = occupied & ROOK_MASK[sq];
        return ROOK_ATTACKS[sq][(int) ((blockers * ROOK_MAGIC[sq]) >>> ROOK_SHIFT[sq])];
    }

    public static long bishop(int sq, long occupied) {
        long blockers = occupied & BISHOP_MASK[sq];
        return BISHOP_ATTACKS[sq][(int) ((blockers * BISHOP_MAGIC[sq]) >>> BISHOP_SHIFT[sq])];
    }

    public static long queen(int sq, long occupied) {
        return rook(sq, occupied) | bishop(sq, occupied);
    }

    /** Squares whose occupancy can change this piece's attacks. Edges excluded. */
    private static long relevantMask(int sq, int[][] dirs) {
        long mask = 0L;
        int f = Square.file(sq), r = Square.rank(sq);
        for (int[] d : dirs) {
            int ff = f + d[0], rr = r + d[1];
            while (ff >= 0 && ff < 8 && rr >= 0 && rr < 8) {
                int nf = ff + d[0], nr = rr + d[1];
                boolean beyondBoard = nf < 0 || nf > 7 || nr < 0 || nr > 7;
                if (!beyondBoard) mask |= 1L << Square.of(ff, rr);
                ff = nf;
                rr = nr;
            }
        }
        return mask;
    }

    /** Attacks by walking rays. The reference these tables are built from. */
    private static long rayAttacks(int sq, long occupied, int[][] dirs) {
        long attacks = 0L;
        int f = Square.file(sq), r = Square.rank(sq);
        for (int[] d : dirs) {
            int ff = f + d[0], rr = r + d[1];
            while (ff >= 0 && ff < 8 && rr >= 0 && rr < 8) {
                int s = Square.of(ff, rr);
                attacks |= 1L << s;
                if ((occupied & (1L << s)) != 0L) break;
                ff += d[0];
                rr += d[1];
            }
        }
        return attacks;
    }

    /** The n-th subset of a mask's set bits, used to enumerate every blocker pattern. */
    private static long subset(int index, long mask) {
        long result = 0L;
        long bits = mask;
        for (int i = 0; bits != 0L; i++) {
            int bit = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            if ((index & (1 << i)) != 0) result |= 1L << bit;
        }
        return result;
    }

    private static long[] build(int sq, long mask, int shift, int[][] dirs,
                                SplittableRandom rng, long[] magicOut) {
        int bits = 64 - shift;
        int size = 1 << bits;
        long[] blockers = new long[size];
        long[] expected = new long[size];
        for (int i = 0; i < size; i++) {
            blockers[i] = subset(i, mask);
            expected[i] = rayAttacks(sq, blockers[i], dirs);
        }

        long[] table = new long[size];
        for (int attempt = 0; attempt < 10_000_000; attempt++) {
            // Sparse candidates (AND of three randoms) collide far less often.
            long magic = rng.nextLong() & rng.nextLong() & rng.nextLong();
            if (Long.bitCount((mask * magic) >>> 56) < 6) continue;

            java.util.Arrays.fill(table, 0L);
            boolean ok = true;
            for (int i = 0; i < size; i++) {
                int index = (int) ((blockers[i] * magic) >>> shift);
                if (table[index] == 0L) {
                    table[index] = expected[i];
                } else if (table[index] != expected[i]) {
                    ok = false;   // two patterns, one slot, different answers
                    break;
                }
            }
            if (ok) {
                magicOut[sq] = magic;
                return table.clone();
            }
        }
        throw new IllegalStateException("no magic found for square " + sq);
    }
}
