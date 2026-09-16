package strix.core;

/**
 * Squares are 0-63 with a1=0, b1=1, ... h1=7, a2=8, ... h8=63.
 * So file = sq &amp; 7 and rank = sq &gt;&gt;&gt; 3, and "one rank up" is +8.
 */
public final class Square {

    public static final int NONE = -1;

    public static final int A1 = 0,  E1 = 4,  G1 = 6,  C1 = 2,  H1 = 7,  D1 = 3, F1 = 5;
    public static final int A8 = 56, E8 = 60, G8 = 62, C8 = 58, H8 = 63, D8 = 59, F8 = 61;

    private Square() {}

    public static int of(int file, int rank) { return (rank << 3) | file; }
    public static int file(int sq) { return sq & 7; }
    public static int rank(int sq) { return sq >>> 3; }

    public static String name(int sq) {
        return "" + (char) ('a' + file(sq)) + (char) ('1' + rank(sq));
    }

    public static int fromName(String s) {
        return of(s.charAt(0) - 'a', s.charAt(1) - '1');
    }
}
