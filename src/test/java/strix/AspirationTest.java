package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.Fen;
import strix.core.Move;
import strix.eval.Psqt;
import strix.search.Ordering;
import strix.search.Search;
import strix.search.SearchLimits;
import strix.search.TranspositionTable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An aspiration window is an optimization, so it must not change the answer.
 *
 * The failure mode is specific and quiet: a search that fails high or low
 * returns a BOUND, not a score. An implementation that accepts that bound
 * without widening and re-searching will report a confident evaluation that is
 * merely the edge of the window it guessed, and the engine plays on a number it
 * invented.
 */
class AspirationTest {

    /** Mirrors Search.ASPIRATION_WINDOW, which is private. */
    private static final int ASPIRATION_HALF_WIDTH = 25;

    private static final String[] POSITIONS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
    };

    private static Search engine(boolean aspiration) {
        return engine(aspiration, true);
    }

    /**
     * @param tt whether to give the search a transposition table.
     *
     * Score equality is only exact without one. A narrow-window search stores
     * BOUNDS, and a later wider search at the same depth can legitimately reuse
     * them and return a bound instead of re-deriving an exact score. Measured on
     * a queen-up position: identical at every depth with the table off, and
     * differing at depth 4 with it on (945 against 895).
     *
     * That is an accepted property of aspiration plus a transposition table, not
     * a defect, which is why the strict score test runs without one and the move
     * test runs with one.
     */
    private static Search engine(boolean aspiration, boolean tt) {
        Search s = new Search(new Psqt());
        s.useAspiration = aspiration;
        // LMR off: it is a heuristic that changes scores, so leaving it on would
        // make this a two-variable comparison.
        s.useLmr = false;
        s.useLmp = false;
        s.useFutility = false;
        s.ordering = new Ordering();
        if (tt) s.tt = new TranspositionTable(32);
        return s;
    }

    private static SearchLimits toDepth(int depth) {
        SearchLimits l = new SearchLimits();
        l.depth = depth;
        return l;
    }

    @Test @DisplayName("Aspiration returns the same score as a full window")
    void sameScore() {
        for (String fen : POSITIONS) {
            for (int depth = 4; depth <= 7; depth++) {
                Search wide = engine(false, false);
                int wideScore = wide.think(Fen.parse(fen), toDepth(depth));

                Search narrow = engine(true, false);
                int narrowScore = narrow.think(Fen.parse(fen), toDepth(depth));

                final int d = depth;
                assertEquals(wideScore, narrowScore,
                        () -> "aspiration changed the score at depth " + d + ": " + fen);
            }
        }
    }

    @Test @DisplayName("Aspiration returns the same move as a full window")
    void sameMove() {
        for (String fen : POSITIONS) {
            Search wide = engine(false);
            wide.think(Fen.parse(fen), toDepth(6));
            Search narrow = engine(true);
            narrow.think(Fen.parse(fen), toDepth(6));
            assertEquals(Move.toUci(wide.bestMove), Move.toUci(narrow.bestMove),
                    "aspiration changed the move on " + fen);
        }
    }

    @Test @DisplayName("A score far outside the window is still found, not clipped to its edge")
    void recoversFromAFailedWindow() {
        // A large material imbalance puts the true score far outside any window
        // centred on a previous iteration, which is exactly where a missing
        // re-search shows up: the reported score would sit at the window edge
        // instead of the truth. Black is a queen down.
        String swing = "rnb1kbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1";
        Search wide = engine(false, false);
        int wideScore = wide.think(Fen.parse(swing), toDepth(6));
        Search narrow = engine(true, false);
        int narrowScore = narrow.think(Fen.parse(swing), toDepth(6));

        assertEquals(wideScore, narrowScore, "window edge leaked into the score");
        assertTrue(Math.abs(narrowScore) > ASPIRATION_HALF_WIDTH,
                "a queen of material should land far outside one window half-width, got "
                        + narrowScore);
    }

    @Test @DisplayName("Mate scores bypass the window rather than being clipped by it")
    void mateScoresBypassTheWindow() {
        // A mate score is enormous, so a window around the previous score would
        // fail every time and re-search to infinity anyway. Skipping is both
        // faster and safer.
        String mateIn1 = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1";
        Search narrow = engine(true);
        int score = narrow.think(Fen.parse(mateIn1), toDepth(6));
        assertTrue(Math.abs(score) > 10000, "should report a mate score, got " + score);
        assertNotEquals(Move.NONE, narrow.bestMove);
    }

    @Test @DisplayName("With a table, the move still matches even where the score may not")
    void moveMatchesEvenWithATable() {
        // The score can legitimately differ here (see engine(boolean, boolean)),
        // but the move is what gets played, so it is the property that has to
        // hold with the engine configured the way it actually runs.
        for (String fen : POSITIONS) {
            for (int depth = 4; depth <= 7; depth++) {
                Search wide = engine(false, true);
                wide.think(Fen.parse(fen), toDepth(depth));
                Search narrow = engine(true, true);
                narrow.think(Fen.parse(fen), toDepth(depth));
                final int d = depth;
                assertEquals(Move.toUci(wide.bestMove), Move.toUci(narrow.bestMove),
                        () -> "aspiration changed the move at depth " + d + ": " + fen);
            }
        }
    }

    @Test @DisplayName("A depth-limited search is untimed, and therefore reproducible")
    void depthLimitedSearchIsDeterministic() {
        // "go depth N" means search that far and stop, with no time limit. A
        // fallback budget applied here made the search finish at whatever depth
        // the wall clock allowed, so under load the same position returned 945
        // on one run and 895 on the next. Every reproducibility test in this
        // repo depends on that not happening.
        String fen = "rnb1kbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1";
        int first = engine(false, false).think(Fen.parse(fen), toDepth(5));
        for (int i = 0; i < 4; i++) {
            assertEquals(first, engine(false, false).think(Fen.parse(fen), toDepth(5)),
                    "depth-limited search is not reproducible");
        }

        SearchLimits noLimitsAtAll = new SearchLimits();
        assertEquals(Long.MAX_VALUE, toDepth(5).allocate(strix.core.Piece.WHITE),
                "an explicit depth must not be time-limited");
        assertTrue(noLimitsAtAll.allocate(strix.core.Piece.WHITE) < Long.MAX_VALUE,
                "but a bare go, with no clock and no depth, still needs a budget");
    }
}