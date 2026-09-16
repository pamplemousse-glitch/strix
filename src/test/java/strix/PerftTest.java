package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import strix.core.Board;
import strix.core.Fen;
import strix.core.Perft;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The oracle for this entire project.
 *
 * Node counts verified against https://www.chessprogramming.org/Perft_Results
 * on 2026-09-15. Do not "fix" a number here to make a test pass. If the engine
 * disagrees with these, the engine is wrong.
 *
 * Debugging a mismatch: use Perft.divide() and diff against Stockfish's
 * `go perft N` for the same position. Exactly one move will disagree. Make that
 * move and repeat one ply down until you reach the position where they diverge.
 */
class PerftTest {

    private static final String START =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String KIWIPETE =
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    private static final String POS3 =
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
    private static final String POS4 =
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
    private static final String POS5 =
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";
    private static final String POS6 =
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10";

    private static void perft(String fen, int depth, long expected) {
        Board b = Fen.parse(fen);
        assertEquals(expected, Perft.count(b, depth),
                () -> "perft(" + depth + ") wrong for " + fen);
    }

    @Test @DisplayName("Position 1: starting position")
    void position1() {
        perft(START, 1, 20L);
        perft(START, 2, 400L);
        perft(START, 3, 8_902L);
        perft(START, 4, 197_281L);
        perft(START, 5, 4_865_609L);
    }

    @Test @DisplayName("Position 2: Kiwipete, the castling and pin trap")
    void position2() {
        perft(KIWIPETE, 1, 48L);
        perft(KIWIPETE, 2, 2_039L);
        perft(KIWIPETE, 3, 97_862L);
        perft(KIWIPETE, 4, 4_085_603L);
    }

    @Test @DisplayName("Position 3: pawn endgame, en passant edge cases")
    void position3() {
        perft(POS3, 1, 14L);
        perft(POS3, 2, 191L);
        perft(POS3, 3, 2_812L);
        perft(POS3, 4, 43_238L);
        perft(POS3, 5, 674_624L);
        perft(POS3, 6, 11_030_083L);
    }

    @Test @DisplayName("Position 4: promotions under check")
    void position4() {
        perft(POS4, 1, 6L);
        perft(POS4, 2, 264L);
        perft(POS4, 3, 9_467L);
        perft(POS4, 4, 422_333L);
    }

    @Test @DisplayName("Position 5: promotion tactics")
    void position5() {
        perft(POS5, 1, 44L);
        perft(POS5, 2, 1_486L);
        perft(POS5, 3, 62_379L);
        perft(POS5, 4, 2_103_487L);
    }

    @Test @DisplayName("Position 6: quiet middlegame")
    void position6() {
        perft(POS6, 1, 46L);
        perft(POS6, 2, 2_079L);
        perft(POS6, 3, 89_890L);
        perft(POS6, 4, 3_894_594L);
    }

    @Test @Tag("deep") @DisplayName("Deep: 119M nodes, nightly only")
    void deep() {
        perft(START, 6, 119_060_324L);
        perft(KIWIPETE, 5, 193_690_690L);
        perft(POS3, 7, 178_633_661L);
        perft(POS4, 5, 15_833_292L);
        perft(POS5, 5, 89_941_194L);
        perft(POS6, 5, 164_075_551L);
    }
}
