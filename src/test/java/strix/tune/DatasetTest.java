package strix.tune;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatasetTest {

    private static final String FEN = "r2qkb1r/pppb1ppp/2n1p3/4P3/P2Pp3/4B3/1PP1NPPP/R2QKB1R b KQkq - 3 8";

    @Test @DisplayName("Three-field line carries the game id")
    void parsesCurrentFormat() {
        Dataset.Row r = Dataset.parse(FEN + " | -85 | 1742");
        assertNotNull(r);
        assertEquals(FEN, r.fen());
        assertEquals("-85", r.label());
        assertEquals(1742, r.gameId());
    }

    @Test @DisplayName("Two-field line still reads, with no game id")
    void parsesLegacyFormat() {
        // runs/labelled.txt predates the id. It has to keep loading.
        Dataset.Row r = Dataset.parse(FEN + " | -85");
        assertNotNull(r);
        assertEquals(FEN, r.fen());
        assertEquals("-85", r.label());
        assertEquals(Dataset.NO_GAME, r.gameId());
    }

    @Test @DisplayName("The FEN comes from the FIRST bar, not the last")
    void fenIsNotGreedy() {
        // The bug this guards: lastIndexOf('|') on a three-field line returns the
        // FEN *and* the label as the FEN, and the game id as the label. It does
        // not throw, it silently trains on garbage.
        Dataset.Row r = Dataset.parse(FEN + " | 0.5 | 7");
        assertEquals(FEN, r.fen());
        assertEquals("0.5", r.label());
        assertFalse(r.fen().contains("|"));
    }

    @Test @DisplayName("Texel labels survive the round trip")
    void roundTripsTexelLabels() {
        for (String label : new String[]{"1.0", "0.0", "0.5"}) {
            Dataset.Row r = Dataset.parse(Dataset.format(FEN, label, 3));
            assertEquals(label, r.label());
            assertEquals(3, r.gameId());
        }
    }

    @Test @DisplayName("Formatting omits the id when there is none")
    void formatOmitsMissingId() {
        assertEquals(FEN + " | -85", Dataset.format(FEN, "-85", Dataset.NO_GAME));
        assertEquals(FEN + " | -85 | 0", Dataset.format(FEN, "-85", 0));
    }

    @Test @DisplayName("Junk is skipped rather than parsed into nonsense")
    void rejectsJunk() {
        assertNull(Dataset.parse(null));
        assertNull(Dataset.parse(""));
        assertNull(Dataset.parse("   "));
        assertNull(Dataset.parse("# a comment"));
        assertNull(Dataset.parse("no bar at all"));
        assertNull(Dataset.parse(" | -85"));          // no FEN
        assertNull(Dataset.parse(FEN + " | "));       // no label
    }

    @Test @DisplayName("An unparseable id degrades to no id, not an exception")
    void badIdIsNotFatal() {
        Dataset.Row r = Dataset.parse(FEN + " | -85 | notanumber");
        assertNotNull(r);
        assertEquals("-85", r.label());
        assertEquals(Dataset.NO_GAME, r.gameId());
    }
}
