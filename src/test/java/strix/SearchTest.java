package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.Board;
import strix.core.Fen;
import strix.core.Move;
import strix.eval.Material;
import strix.search.Ordering;
import strix.search.Search;

import static org.junit.jupiter.api.Assertions.*;

class SearchTest {

    private static final String[] POSITIONS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
    };

    @Test @DisplayName("Finds mate in one")
    void mateInOne() {
        // Back rank mate: Ra8#
        Board b = Fen.parse("6k1/5ppp/8/8/8/8/8/R3K3 w Q - 0 1");
        Search s = new Search(new Material());
        int score = s.alphaBeta(b, 2);
        assertTrue(score > Search.MATE - 100, "expected a mate score, got " + score);
        assertEquals("a1a8", Move.toUci(s.bestMove));
    }

    @Test @DisplayName("Prefers winning material")
    void takesTheQueen() {
        // White rook on d1 can take an undefended black queen on d8.
        Board b = Fen.parse("3q2k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1");
        Search s = new Search(new Material());
        s.alphaBeta(b, 2);
        assertEquals("d1d8", Move.toUci(s.bestMove));
    }

    /**
     * The invariant this whole stage exists to establish.
     *
     * Alpha-beta prunes only branches it has already proven cannot affect the
     * result, so it must return the same score as unpruned negamax. Scores are
     * compared rather than moves, because equal-scoring moves are common and
     * which one is reported is an arbitrary tie-break.
     */
    @Test @DisplayName("Alpha-beta: same score as negamax, fewer nodes")
    void alphaBetaIsAnOptimizationNotAnImprovement() {
        for (String fen : POSITIONS) {
            for (int depth = 1; depth <= 4; depth++) {
                Search plain = new Search(new Material());
                plain.useQuiescence = false;
                int plainScore = plain.negamax(Fen.parse(fen), depth);
                long plainNodes = plain.nodes;

                Search pruned = new Search(new Material());
                pruned.useQuiescence = false;
                // Null move and LMR are heuristics, not exact optimizations:
                // they can and do change the score. Only exact techniques belong
                // in a comparison against unpruned negamax.
                pruned.useNullMove = false;
                pruned.useLmr = false;
                int prunedScore = pruned.alphaBeta(Fen.parse(fen), depth);
                long prunedNodes = pruned.nodes;

                final int d = depth;
                assertEquals(plainScore, prunedScore,
                        () -> "alpha-beta changed the answer at depth " + d + ": " + fen);
                assertTrue(prunedNodes <= plainNodes,
                        () -> "alpha-beta searched MORE nodes at depth " + d + ": "
                                + prunedNodes + " vs " + plainNodes + "  " + fen);
            }
        }
    }

    @Test @DisplayName("Quiescence kills the odd/even score oscillation")
    void quiescenceStopsOscillation() {
        // After 1.e4 e5. Without quiescence the score swings a full pawn between
        // depths because the search stops mid-recapture.
        Board start = Fen.parse(Fen.START);
        int[] m = new int[256];
        int n = strix.core.MoveGen.generateLegal(start, m, new int[256]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals("e2e4")) start.make(m[i]);
        n = strix.core.MoveGen.generateLegal(start, m, new int[256]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals("e7e5")) start.make(m[i]);

        int prev = 0;
        for (int d = 2; d <= 5; d++) {
            Search s = new Search(new Material());
            int score = s.alphaBeta(Fen.emit(start) == null ? start : Fen.parse(Fen.emit(start)), d);
            if (d > 2) {
                assertTrue(Math.abs(score - prev) < 100,
                        "score swung " + prev + " -> " + score + " at depth " + d
                                + "; quiescence is not doing its job");
            }
            prev = score;
        }
    }

    @Test @DisplayName("Pruning actually saves a lot at depth 4")
    void pruningIsSubstantial() {
        String fen = POSITIONS[1]; // Kiwipete, wide and tactical
        Search plain = new Search(new Material());
        plain.useQuiescence = false;
        plain.negamax(Fen.parse(fen), 4);
        Search pruned = new Search(new Material());
        pruned.useQuiescence = false;
        pruned.alphaBeta(Fen.parse(fen), 4);

        double ratio = (double) pruned.nodes / plain.nodes;
        System.out.printf("Kiwipete depth 4: negamax %,d nodes, alpha-beta %,d nodes (%.1f%%)%n",
                plain.nodes, pruned.nodes, ratio * 100);
        assertTrue(ratio < 0.5,
                "expected alpha-beta to cut well over half the tree, got " + (ratio * 100) + "%");
    }

    /**
     * The same invariant, over a corpus rather than a curated handful.
     *
     * The six-FEN list above is a safety net with a hole in it: it passed while
     * negamax was missing the repetition, fifty-move and insufficient-material
     * rules that alphaBeta applies, so the two were not computing the same
     * function at all. Four of 150 random self-play positions disagreed at depth
     * 4. None of the six caught it, because none of them reach a draw rule
     * within four plies.
     *
     * Reads the generated dataset when it exists and skips otherwise, so the
     * suite stays runnable on a fresh clone.
     */
    @Test @DisplayName("The invariant holds over a corpus, not just six positions")
    void invariantHoldsOverManyPositions() throws Exception {
        java.nio.file.Path data = java.nio.file.Path.of("runs/positions.txt");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(data),
                "runs/positions.txt not generated");

        java.util.List<String> lines = java.nio.file.Files.readAllLines(data);
        java.util.Collections.shuffle(lines, new java.util.Random(7));

        int checked = 0;
        for (String line : lines) {
            int bar = line.indexOf('|');
            if (bar < 0) continue;
            String fen = line.substring(0, bar).trim();

            Board a, b;
            try { a = Fen.parse(fen); b = Fen.parse(fen); } catch (RuntimeException e) { continue; }

            Search plain = new Search(new Material());
            plain.useQuiescence = false;
            Search pruned = new Search(new Material());
            pruned.useQuiescence = false;
            // Exact techniques only. Null move and LMR are heuristics and are
            // allowed to change the score; comparing them against unpruned
            // negamax would be asserting that a heuristic is not a heuristic.
            pruned.useNullMove = false;
            pruned.useLmr = false;

            assertEquals(plain.negamax(a, 4), pruned.alphaBeta(b, 4),
                    () -> "negamax and alpha-beta disagree on " + fen);
            if (++checked >= 60) break;
        }
        assertTrue(checked > 0, "no usable positions in the corpus");
    }

    /**
     * PVS is an optimization, so it must not change the answer.
     *
     * Same contract as alpha-beta against negamax: a null-window search returns
     * a BOUND, not a value, so a PVS implementation that forgets to re-search
     * when the null window fails high will silently return the bound as if it
     * were a score. That produces plausible moves and wrong evaluations, which
     * is the failure mode this test exists to refuse.
     */
    @Test @DisplayName("PVS: same score as plain alpha-beta, no more nodes")
    void pvsIsAnOptimizationNotAnImprovement() {
        for (String fen : POSITIONS) {
            for (int depth = 1; depth <= 5; depth++) {
                // Null move is off on both sides on purpose. It only fires at
                // non-PV nodes (beta == alpha + 1), and without PVS there are no
                // such nodes, so leaving it on would silently make this a
                // two-variable comparison.
                Search plain = new Search(new Material());
                plain.usePvs = false;
                plain.useNullMove = false;
                plain.useLmr = false;
                plain.ordering = new Ordering();
                int plainScore = plain.alphaBeta(Fen.parse(fen), depth);
                long plainNodes = plain.nodes;

                Search pvs = new Search(new Material());
                pvs.usePvs = true;
                pvs.useNullMove = false;
                pvs.useLmr = false;
                pvs.ordering = new Ordering();
                int pvsScore = pvs.alphaBeta(Fen.parse(fen), depth);
                long pvsNodes = pvs.nodes;

                final int d = depth;
                assertEquals(plainScore, pvsScore,
                        () -> "PVS changed the answer at depth " + d + ": " + fen);
                assertTrue(pvsNodes <= plainNodes * 1.05,
                        () -> "PVS searched more nodes at depth " + d + ": "
                                + pvsNodes + " vs " + plainNodes + "  " + fen);
            }
        }
    }

    @Test @DisplayName("PVS agrees with negamax too, so the whole chain holds")
    void pvsAgreesWithTheReference() {
        for (String fen : POSITIONS) {
            Search plain = new Search(new Material());
            plain.useQuiescence = false;
            plain.useNullMove = false;
            plain.useLmr = false;
            Search pvs = new Search(new Material());
            pvs.useQuiescence = false;
            pvs.useNullMove = false;
            pvs.useLmr = false;
            pvs.usePvs = true;
            pvs.ordering = new Ordering();
            assertEquals(plain.negamax(Fen.parse(fen), 4), pvs.alphaBeta(Fen.parse(fen), 4),
                    () -> "PVS disagrees with unpruned negamax on " + fen);
        }
    }
}