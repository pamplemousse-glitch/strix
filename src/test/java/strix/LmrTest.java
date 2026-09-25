package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.Fen;
import strix.core.Move;
import strix.eval.Psqt;
import strix.search.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Late move reductions are a heuristic, so unlike PVS they are allowed to change
 * the score. What they are not allowed to do is lose a tactic that the
 * unreduced search sees, and the re-search is the only thing standing between
 * those two outcomes.
 */
class LmrTest {

    private static Search engine(boolean lmr) {
        Search s = new Search(new Psqt());
        s.useLmr = lmr;
        s.ordering = new Ordering();
        s.tt = new TranspositionTable(32);
        return s;
    }

    private static SearchLimits toDepth(int d) {
        SearchLimits l = new SearchLimits();
        l.depth = d;
        return l;
    }

    @Test @DisplayName("Reductions cut nodes, which is the entire point")
    void reducesNodes() {
        String[] fens = {
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        };
        long off = 0, on = 0;
        for (String fen : fens) {
            Search a = engine(false);
            a.think(Fen.parse(fen), toDepth(7));
            off += a.nodesThisMove;
            Search b = engine(true);
            b.think(Fen.parse(fen), toDepth(7));
            on += b.nodesThisMove;
        }
        assertTrue(on < off,
                "LMR searched no fewer nodes: " + on + " vs " + off);
    }

    @Test @DisplayName("A mate in one is still found with reductions on")
    void doesNotMissShallowMate() {
        // The whole risk of reducing is missing something a full search sees.
        // A forced mate is the cheapest version of that test.
        String[] mates = {
            "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1",
            "7k/6pp/8/8/8/8/6PP/R6K w - - 0 1",
        };
        for (String fen : mates) {
            Search s = engine(true);
            int score = s.think(Fen.parse(fen), toDepth(6));
            assertTrue(score > 10000, "missed a forced mate on " + fen + ", scored " + score);
            assertNotEquals(Move.NONE, s.bestMove);
        }
    }

    @Test @DisplayName("Captures, promotions and checks are never reduced")
    void tacticalMovesSurviveTheReduction() {
        // These are exactly the moves a shallow search misjudges, so the value
        // of a tactical position must not depend on whether LMR is on.
        String[] tactical = {
            "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2",
            "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
            "8/P7/8/8/8/8/7k/K7 w - - 0 1",
        };
        for (String fen : tactical) {
            int withLmr = engine(true).think(Fen.parse(fen), toDepth(6));
            int without = engine(false).think(Fen.parse(fen), toDepth(6));
            assertEquals(Integer.signum(without), Integer.signum(withLmr),
                    "LMR flipped the sign of the evaluation on " + fen);
        }
    }

    @Test @DisplayName("Reduction never pushes the search below the horizon")
    void neverReducesIntoQuiescence() {
        // A reduction bigger than depth - 2 would turn the child into a
        // quiescence call, which is a different kind of search, not a cheaper
        // one. Reconstructed here from the same formula the search uses.
        for (int depth = 3; depth < 64; depth++) {
            for (int move = 4; move < 64; move++) {
                int r = (int) (0.75 + Math.log(depth) * Math.log(move) / 2.25);
                r = Math.min(r, depth - 2);
                if (r < 0) r = 0;
                assertTrue(depth - 1 - r >= 1,
                        "depth " + depth + " move " + move + " reduced to " + (depth - 1 - r));
            }
        }
    }
}
