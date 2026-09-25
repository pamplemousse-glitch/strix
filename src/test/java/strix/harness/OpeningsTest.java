package strix.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.*;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpeningsTest {

    @Test @DisplayName("The first cycle is the book, unchanged")
    void firstCycleIsTheBook() {
        for (int p = 0; p < Openings.size(); p++) {
            assertEquals(Openings.get(p), Openings.lineFor(p));
        }
    }

    @Test @DisplayName("Past the book, a pair is not a replay of an earlier one")
    void laterCyclesAreDistinct() {
        // The defect: the harness asked for p % size(), and the search is
        // deterministic at fixed nodes, so pair 0 and pair 48 played the
        // identical game. texel-sprt.tsv logged 280 pairs and 48 distinct
        // results. The LLR is linear in the counts, so that multiplied it by
        // ~5.8 while adding no information.
        Set<String> seen = new HashSet<>();
        int n = Openings.size() * 4;
        for (int p = 0; p < n; p++) seen.add(Openings.lineFor(p));
        assertEquals(n, seen.size(), "every pair index must give a distinct line");
    }

    @Test @DisplayName("Deterministic per pair, which the cluster dedupe depends on")
    void deterministicPerPair() {
        // Two workers handed the same pair must produce the same games, or the
        // coordinator cannot drop a duplicate result without reconciling it.
        for (int p : new int[]{0, 47, 48, 95, 144, 1000}) {
            assertEquals(Openings.lineFor(p), Openings.lineFor(p),
                    "pair " + p + " must be reproducible");
        }
    }

    @Test @DisplayName("Every generated line is legal and leaves a playable position")
    void linesAreLegalAndPlayable() {
        for (int p = 0; p < Openings.size() * 3; p++) {
            Board b = Fen.parse(Fen.START);
            for (String u : Openings.lineFor(p).trim().split("\\s+")) {
                int m = resolve(b, u);
                assertNotEquals(Move.NONE, m, "illegal move " + u + " in pair " + p);
                b.make(m);
            }
            // A pair starting from a finished position is not an observation.
            assertFalse(GameResult.of(b).isOver(), "pair " + p + " starts from a finished game");
        }
    }

    private static int resolve(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals(uci)) return m[i];
        return Move.NONE;
    }
}
