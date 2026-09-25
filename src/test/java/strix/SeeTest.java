package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.*;
import strix.search.See;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every expectation below is arithmetic on piece values, computed by hand from
 * the position rather than read off the implementation.
 */
class SeeTest {

    private static final int PAWN = 100, KNIGHT = 320, BISHOP = 330, ROOK = 500;

    private static int see(String fen, String uci) {
        Board b = Fen.parse(fen);
        int[] moves = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, moves, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) {
            if (Move.toUci(moves[i]).equals(uci)) return See.evaluate(b, moves[i]);
        }
        throw new IllegalArgumentException(uci + " is not legal in " + fen);
    }

    @Test @DisplayName("Taking a free pawn wins a pawn")
    void freePawn() {
        assertEquals(PAWN, see("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5"));
    }

    @Test @DisplayName("Pawn takes defended pawn is an even trade")
    void evenTrade() {
        // exd5 cxd5: +100 -100.
        assertEquals(0, see("4k3/8/2p5/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5"));
    }

    @Test @DisplayName("Rook takes defended pawn loses the exchange")
    void rookTakesDefendedPawn() {
        // Rxe5 dxe5: +100 -500. This is exactly what MVV-LVA cannot see, since
        // it only knows the rook is taking something.
        assertEquals(PAWN - ROOK, see("4k3/8/3p4/4p3/8/8/8/4R1K1 w - - 0 1", "e1e5"));
    }

    @Test @DisplayName("Queen takes defended pawn loses more")
    void queenTakesDefendedPawn() {
        assertEquals(PAWN - 900, see("4k3/8/3p4/4p3/8/8/8/4Q1K1 w - - 0 1", "e1e5"));
    }

    @Test @DisplayName("A defender that cannot afford to recapture is not counted")
    void recaptureIsOptional() {
        // Nxd5 and the only recapture is the KING, which would then be taken by
        // nothing: the pawn is simply won. The fold-back is what gets this
        // right, because each side may decline.
        assertEquals(PAWN, see("4k3/8/8/3p4/8/4N3/8/4K3 w - - 0 1", "e3d5"));
    }

    @Test @DisplayName("Two attackers beat one defender")
    void extraAttackerWinsThePawn() {
        // Pawn on d5 defended once by c6, attacked by the e4 pawn and a knight
        // on e3 (a knight on d2 does not reach d5, which is how this test first
        // failed: the position, not the code).
        // exd5 cxd5 Nxd5: +100 -100 +100.
        assertEquals(PAWN, see("4k3/8/2p5/3p4/4P3/4N3/8/4K3 w - - 0 1", "e4d5"));
    }

    @Test @DisplayName("An x-ray joins the exchange when the piece in front moves")
    void xrayIsSeen() {
        // Rooks doubled on e1/e2 against a pawn on e5 defended by d6.
        // Rxe5 dxe5 Rxe5: +100 -500 +100 = -300, and the second rook only
        // exists in the calculation because occupancy is recomputed each step.
        assertEquals(PAWN - ROOK + PAWN, see("4k3/8/3p4/4p3/8/8/4R3/4R1K1 w - - 0 1", "e2e5"));
    }

    @Test @DisplayName("En passant removes the pawn that is not on the square")
    void enPassant() {
        // exd6 e.p. wins the d5 pawn. The captured pawn sits on d5, not d6, so
        // an implementation that clears only the destination square computes the
        // exchange against a board where it is still defending.
        assertEquals(PAWN, see("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6"));
    }

    @Test @DisplayName("Quiet moves and the classic CPW positions")
    void knownPositions() {
        // A quiet move captures nothing, so an undefended destination is 0.
        assertEquals(0, see("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1", "e2e4"));

        // Chess Programming Wiki SEE example 1: Rxe5 wins a pawn outright.
        assertEquals(PAWN, see("1k1r4/1pp4p/p7/4p3/8/P5P1/1PP4P/2K1R3 w - - 0 1", "e1e5"));

        // CPW example 2: Nxe5 is defended several times over and loses.
        assertTrue(see("1k1r3q/1ppn3p/p4b2/4p3/8/P2N2P1/1PP1R1BP/2K1Q3 w - - 0 1", "d3e5") < 0,
                "Nxe5 into a defended square must be negative");
    }

    @Test @DisplayName("Losing captures are ordered below quiet moves")
    void losingCapturesAreDemoted() {
        // RxP into a defended pawn is worse than an ordinary quiet move, so it
        // must be tried after them, not merely last among the captures.
        Board b = Fen.parse("4k3/8/3p4/4p3/8/8/6R1/4R1K1 w - - 0 1");
        int[] moves = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, moves, new int[MoveGen.MAX_MOVES]);

        strix.search.Ordering ordering = new strix.search.Ordering();
        int losingCaptureAt = -1, lastQuietAt = -1;
        for (int i = 0; i < n; i++) {
            ordering.pickBest(b, moves, n, i, Move.NONE, 0);
            if (Move.toUci(moves[i]).equals("e1e5")) losingCaptureAt = i;
            else if (!Move.isCapture(moves[i])) lastQuietAt = i;
        }
        assertTrue(losingCaptureAt >= 0, "Rxe5 should be legal here");
        assertTrue(losingCaptureAt > lastQuietAt,
                "a losing capture was ordered at " + losingCaptureAt
                        + ", before a quiet move at " + lastQuietAt);
    }

    @Test @DisplayName("SEE ordering does not change what the search concludes")
    void orderingDoesNotChangeTheAnswer() {
        // Ordering is an optimization. It decides what is searched first, never
        // what the search is allowed to conclude, so the score must not move.
        String[] fens = {
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
        };
        for (String fen : fens) {
            for (int depth = 3; depth <= 5; depth++) {
                strix.search.Search off = plain(false);
                strix.search.Search on = plain(true);
                final int d = depth;
                assertEquals(off.alphaBeta(Fen.parse(fen), depth),
                        on.alphaBeta(Fen.parse(fen), depth),
                        () -> "SEE ordering changed the score at depth " + d + ": " + fen);
            }
        }
    }

    private static strix.search.Search plain(boolean see) {
        strix.search.Search s = new strix.search.Search(new strix.eval.Psqt());
        s.ordering = new strix.search.Ordering();
        s.ordering.useSee = see;
        // Quiescence SEE pruning is a heuristic and discards moves, so it is
        // held off here: this test is about ordering alone.
        s.useSeePruning = false;
        return s;
    }
}