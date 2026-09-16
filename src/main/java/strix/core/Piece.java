package strix.core;

/** Piece type and color constants. Bitboards are indexed color * 6 + type. */
public final class Piece {

    public static final int PAWN   = 0;
    public static final int KNIGHT = 1;
    public static final int BISHOP = 2;
    public static final int ROOK   = 3;
    public static final int QUEEN  = 4;
    public static final int KING   = 5;

    public static final int WHITE = 0;
    public static final int BLACK = 1;

    public static final int NONE = -1;

    private Piece() {}

    public static int index(int color, int type) { return color * 6 + type; }
    public static int colorOf(int index) { return index / 6; }
    public static int typeOf(int index)  { return index % 6; }

    public static int other(int color) { return color ^ 1; }

    private static final char[] CHARS = {'P','N','B','R','Q','K','p','n','b','r','q','k'};

    public static char toChar(int index) { return CHARS[index]; }

    public static int fromChar(char c) {
        for (int i = 0; i < CHARS.length; i++) {
            if (CHARS[i] == c) return i;
        }
        return NONE;
    }
}
