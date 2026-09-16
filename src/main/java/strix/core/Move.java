package strix.core;

/**
 * A move packed into a single int. No object allocation in the search loop:
 * a Move object per node would push millions of allocations per second through
 * the GC. See docs/adr/0004.
 *
 * Layout:
 *   bits  0-5   from square (0-63)
 *   bits  6-11  to square   (0-63)
 *   bits 12-15  flag        (see constants)
 */
public final class Move {

    public static final int QUIET            = 0;
    public static final int DOUBLE_PUSH      = 1;
    public static final int CASTLE_KING      = 2;
    public static final int CASTLE_QUEEN     = 3;
    public static final int CAPTURE          = 4;
    public static final int EP_CAPTURE       = 5;
    public static final int PROMO_N          = 8;
    public static final int PROMO_B          = 9;
    public static final int PROMO_R          = 10;
    public static final int PROMO_Q          = 11;
    public static final int PROMO_N_CAPTURE  = 12;
    public static final int PROMO_B_CAPTURE  = 13;
    public static final int PROMO_R_CAPTURE  = 14;
    public static final int PROMO_Q_CAPTURE  = 15;

    public static final int NONE = 0;

    private Move() {}

    public static int of(int from, int to, int flag) {
        return from | (to << 6) | (flag << 12);
    }

    public static int from(int move) { return move & 0x3F; }
    public static int to(int move)   { return (move >>> 6) & 0x3F; }
    public static int flag(int move) { return (move >>> 12) & 0xF; }

    public static boolean isPromotion(int move) { return flag(move) >= PROMO_N; }
    public static boolean isCapture(int move) {
        int f = flag(move);
        return f == CAPTURE || f == EP_CAPTURE || f >= PROMO_N_CAPTURE;
    }
    public static boolean isCastle(int move) {
        int f = flag(move);
        return f == CASTLE_KING || f == CASTLE_QUEEN;
    }

    /** Piece type a promotion produces, or -1. */
    public static int promoPiece(int move) {
        int f = flag(move);
        if (f < PROMO_N) return -1;
        return switch (f & 3) {
            case 0 -> Piece.KNIGHT;
            case 1 -> Piece.BISHOP;
            case 2 -> Piece.ROOK;
            default -> Piece.QUEEN;
        };
    }

    /** Long algebraic notation, the format UCI speaks: "e2e4", "e7e8q". */
    public static String toUci(int move) {
        StringBuilder sb = new StringBuilder(5);
        sb.append(Square.name(from(move)));
        sb.append(Square.name(to(move)));
        int p = promoPiece(move);
        if (p >= 0) sb.append("nbrq".charAt(p - Piece.KNIGHT));
        return sb.toString();
    }
}
