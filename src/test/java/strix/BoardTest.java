package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.Board;
import strix.core.Fen;
import strix.core.Perft;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Step 1 and step 7 gates: FEN round-trips, and color symmetry. */
class BoardTest {

    private static final String[] POSITIONS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
            "8/8/8/2pP4/8/8/8/4K2k w - c6 0 2",
            "8/2p5/8/1P6/8/8/8/4K2k b - - 0 1",
    };

    @Test @DisplayName("FEN round-trips unchanged")
    void fenRoundTrip() {
        for (String fen : POSITIONS) {
            assertEquals(fen, Fen.emit(Fen.parse(fen)), "round-trip failed");
        }
    }

    /**
     * Mirror the board vertically and swap colors. The position is strategically
     * identical, so perft must be identical. Catches the whole class of bugs where
     * one color was handled correctly and the other was not.
     */
    @Test @DisplayName("Flip-board: perft is color-symmetric")
    void flipBoard() {
        for (String fen : POSITIONS) {
            String flipped = flip(fen);
            for (int depth = 1; depth <= 4; depth++) {
                Board a = Fen.parse(fen);
                Board b = Fen.parse(flipped);
                final int d = depth;
                assertEquals(Perft.count(a, d), Perft.count(b, d),
                        () -> "asymmetry at depth " + d + "\n  " + fen + "\n  " + flipped);
            }
        }
    }

    static String flip(String fen) {
        String[] p = fen.trim().split("\\s+");

        String[] ranks = p[0].split("/");
        StringBuilder placement = new StringBuilder();
        for (int i = ranks.length - 1; i >= 0; i--) {
            placement.append(swapCase(ranks[i]));
            if (i > 0) placement.append('/');
        }

        String side = p[1].equals("w") ? "b" : "w";

        String castling = "-";
        if (!p[2].equals("-")) {
            String sw = swapCase(p[2]);
            StringBuilder c = new StringBuilder();
            for (char want : "KQkq".toCharArray()) {
                if (sw.indexOf(want) >= 0) c.append(want);
            }
            castling = c.toString();
        }

        String ep = p[3].equals("-") ? "-"
                : "" + p[3].charAt(0) + (char) ('1' + (7 - (p[3].charAt(1) - '1')));

        String half = p.length > 4 ? p[4] : "0";
        String full = p.length > 5 ? p[5] : "1";
        return placement + " " + side + " " + castling + " " + ep + " " + half + " " + full;
    }

    private static String swapCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c)
                    : Character.isLowerCase(c) ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }
}
