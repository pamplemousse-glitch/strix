package strix.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogAuditTest {

    private static List<String> log(int... pairIndices) {
        List<String> lines = new ArrayList<>();
        for (int p : pairIndices) lines.add(p + "\t2\tWHITE_WINS/BLACK_WINS");
        return lines;
    }

    @Test @DisplayName("Distinct pair indices are distinct evidence")
    void cleanRun() {
        var a = LogAudit.audit(log(0, 1, 2, 3, 4));
        assertEquals(5, a.recorded());
        assertEquals(5, a.distinctLines());
        assertTrue(a.clean());
    }

    @Test @DisplayName("Past the book, pairs sharing a book line are still distinct")
    void diversifiedCyclesAreEvidence() {
        // Pairs 0, 48 and 96 all draw book line 0. Before Openings.lineFor they
        // were the same game; now the line is extended by plies seeded from the
        // pair index, so they are genuinely different starting positions.
        var a = LogAudit.audit(log(0, 48, 96));
        assertEquals(3, a.distinctLines(), "lineFor must diversify past the book");
        assertTrue(a.clean());
    }

    @Test @DisplayName("A genuinely repeated pair index is caught")
    void catchesRepeatedPair() {
        var a = LogAudit.audit(log(0, 0, 0));
        assertEquals(3, a.recorded());
        assertEquals(1, a.distinctLines());
        assertEquals(3.0, a.replication(), 1e-9);
        assertFalse(a.clean());
    }

    @Test @DisplayName("Result strings are not the identity")
    void identityIsTheStartingLineNotTheResult() {
        // The first version of this tool fingerprinted (opening, bucket, detail)
        // and reported a clean 97-pair run as 6.06x replayed, because different
        // games routinely end the same way. Two different pairs with identical
        // results are two observations, not one.
        var a = LogAudit.audit(List.of(
                "0\t2\tWHITE_WINS/WHITE_WINS",
                "1\t2\tWHITE_WINS/WHITE_WINS"));
        assertEquals(2, a.distinctLines());
        assertTrue(a.clean());
    }

    @Test @DisplayName("Legacy opening:pair keys still audit")
    void handlesLegacyKeys() {
        // A legacy run cycled the book with no diversification, so pair 0 and
        // pair 48 really were the same game. The tool must not compute todays
        // line for a log written before todays code.
        var a = LogAudit.audit(List.of(
                "0:0\t2\tA/B",
                "1:1\t3\tC/D",
                "0:48\t1\tE/F"));
        assertTrue(a.legacy());
        assertEquals(3, a.recorded());
        assertEquals(2, a.distinctLines(), "pair 0 and pair 48 shared a book line");
        assertFalse(a.clean());
    }

    @Test @DisplayName("Torn and blank lines are counted as malformed, not as pairs")
    void skipsMalformed() {
        var a = LogAudit.audit(List.of(
                "0\t2\tA/B",
                "",
                "1\t3",          // torn final line after a hard kill
                "garbage\t1\tX/Y"));
        assertEquals(1, a.recorded());
        assertEquals(2, a.malformed());
    }

    @Test @DisplayName("An empty log is not clean, because it is not evidence either")
    void emptyIsNotClean() {
        assertFalse(LogAudit.audit(List.<String>of()).clean());
    }
}
