package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.*;

import static org.junit.jupiter.api.Assertions.*;

class GameResultTest {

    private static Board pos(String fen) { return Fen.parse(fen); }

    @Test @DisplayName("Checkmate and stalemate")
    void mateAndStalemate() {
        assertEquals(GameResult.BLACK_WINS, GameResult.of(pos("7k/8/8/8/8/8/5q2/K6q w - - 0 1")));
        assertEquals(GameResult.DRAW_STALEMATE, GameResult.of(pos("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")));
        assertEquals(GameResult.ONGOING, GameResult.of(pos(Fen.START)));
    }

    @Test @DisplayName("Insufficient material")
    void insufficient() {
        assertEquals(GameResult.DRAW_INSUFFICIENT, GameResult.of(pos("4k3/8/8/8/8/8/8/4K3 w - - 0 1")));
        assertEquals(GameResult.DRAW_INSUFFICIENT, GameResult.of(pos("4k3/8/8/8/8/8/8/3BK3 w - - 0 1")));
        assertEquals(GameResult.DRAW_INSUFFICIENT, GameResult.of(pos("4k3/8/8/8/8/8/8/3NK3 w - - 0 1")));
        // Same-colour bishops cannot mate. Opposite-colour ones can, barely.
        assertEquals(GameResult.DRAW_INSUFFICIENT, GameResult.of(pos("2b1k3/8/8/8/8/8/8/3BK3 w - - 0 1")));
        // A single pawn is always enough, because it can promote.
        assertEquals(GameResult.ONGOING, GameResult.of(pos("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")));
        // Two knights is not insufficient by the strict rule, mate is possible with help.
        assertEquals(GameResult.ONGOING, GameResult.of(pos("4k3/8/8/8/8/8/8/1N1NK3 w - - 0 1")));
    }

    @Test @DisplayName("Fifty-move rule counts plies, not moves")
    void fiftyMove() {
        assertEquals(GameResult.ONGOING, GameResult.of(pos("4k3/8/8/8/8/8/8/R3K3 w - - 99 60")));
        assertEquals(GameResult.DRAW_FIFTY_MOVE, GameResult.of(pos("4k3/8/8/8/8/8/8/R3K3 w - - 100 60")));
    }

    @Test @DisplayName("Threefold repetition")
    void repetition() {
        Board b = pos("3rk3/8/8/8/8/8/8/3RK3 w - - 0 1");
        assertEquals(GameResult.ONGOING, GameResult.of(b));
        // Shuffle rooks back and forth. The third occurrence is a draw.
        String[] cycle = {"d1c1", "d8c8", "c1d1", "c8d8"};
        for (int round = 0; round < 2; round++) {
            for (String u : cycle) b.make(find(b, u));
        }
        assertEquals(GameResult.DRAW_REPETITION, GameResult.of(b),
                "position occurred three times and was not detected");
    }

    @Test @DisplayName("Repetition survives unmake")
    void repetitionUnwinds() {
        Board b = pos("3rk3/8/8/8/8/8/8/3RK3 w - - 0 1");
        String[] cycle = {"d1c1", "d8c8", "c1d1", "c8d8"};
        int[] played = new int[8];
        int i = 0;
        for (int round = 0; round < 2; round++) {
            for (String u : cycle) { int m = find(b, u); played[i++] = m; b.make(m); }
        }
        assertTrue(b.isRepetition(3));
        while (i > 0) b.unmake(played[--i]);
        assertFalse(b.isRepetition(2), "history not unwound by unmake");
    }

    private static int find(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int j = 0; j < n; j++) if (Move.toUci(m[j]).equals(uci)) return m[j];
        throw new IllegalStateException("no legal move " + uci + "\n" + b);
    }
}
