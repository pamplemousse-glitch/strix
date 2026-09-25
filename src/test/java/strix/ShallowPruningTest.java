package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.Fen;
import strix.core.Move;
import strix.eval.Psqt;
import strix.search.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Futility and late move pruning both discard quiet moves at shallow depth
 * without searching them. The danger is specific: prune every move at a node and
 * it returns -INFINITY, reporting a loss that does not exist.
 */
class ShallowPruningTest {

    private static Search engine(boolean pruning) {
        Search s = new Search(new Psqt());
        s.useLmp = pruning;
        s.useFutility = pruning;
        s.ordering = new Ordering();
        s.tt = new TranspositionTable(32);
        return s;
    }

    private static SearchLimits toDepth(int d) {
        SearchLimits l = new SearchLimits();
        l.depth = d;
        return l;
    }

    @Test @DisplayName("No position ever evaluates to negative infinity")
    void neverPrunesEveryMove() {
        // A node that pruned all of its moves would leave bestScore at
        // -INFINITY and hand a fabricated loss up the tree. LMP_COUNT starting
        // above zero is what prevents it, so this is the test that pins that.
        String[] quiet = {
            "8/8/4k3/8/8/4K3/8/8 w - - 0 1",
            "8/5k2/8/5K2/5P2/8/8/8 w - - 0 1",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "8/8/8/3k4/8/3K4/8/7R w - - 0 1",
        };
        for (String fen : quiet) {
            for (int d = 1; d <= 6; d++) {
                Search s = engine(true);
                int score = s.think(Fen.parse(fen), toDepth(d));
                assertTrue(Math.abs(score) < 30000,
                        "fabricated extreme score " + score + " at depth " + d + ": " + fen);
                assertNotEquals(Move.NONE, s.bestMove,
                        "no move returned at depth " + d + ": " + fen);
            }
        }
    }

    @Test @DisplayName("Pruning cuts nodes")
    void prunesNodes() {
        String[] fens = {
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
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
        assertTrue(on < off, "pruning searched no fewer nodes: " + on + " vs " + off);
    }

    @Test @DisplayName("A forced mate survives the pruning")
    void doesNotMissMate() {
        String[] mates = {
            "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1",
            "7k/6pp/8/8/8/8/6PP/R6K w - - 0 1",
        };
        for (String fen : mates) {
            int score = engine(true).think(Fen.parse(fen), toDepth(6));
            assertTrue(score > 10000, "missed a forced mate on " + fen + ", scored " + score);
        }
    }

    @Test @DisplayName("Winning material is still seen, because captures are never pruned")
    void capturesAreNotPruned() {
        // Only quiet moves are eligible. A free queen must survive at every
        // depth where the pruning is active.
        String freeQueen = "rnb1kbnr/pppp1ppp/8/8/7q/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        for (int d = 1; d <= 5; d++) {
            int withPruning = engine(true).think(Fen.parse(freeQueen), toDepth(d));
            int without = engine(false).think(Fen.parse(freeQueen), toDepth(d));
            assertEquals(Integer.signum(without), Integer.signum(withPruning),
                    "pruning flipped the evaluation sign at depth " + d);
        }
    }
}
