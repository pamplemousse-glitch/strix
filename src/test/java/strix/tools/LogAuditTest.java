package strix.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogAuditTest {

    @Test @DisplayName("A run with no repeats is clean")
    void cleanRun() {
        var a = LogAudit.audit(List.of(
                "0:0\t2\tWHITE_WINS/BLACK_WINS",
                "1:1\t3\tDRAW_STALEMATE/WHITE_WINS",
                "2:2\t1\tBLACK_WINS/BLACK_WINS"));
        assertEquals(3, a.recorded());
        assertEquals(3, a.distinct());
        assertEquals(1.0, a.replication(), 1e-9);
        assertTrue(a.clean());
    }

    @Test @DisplayName("The same game replayed under a new pair index is caught")
    void catchesReplay() {
        // This is the real defect: pair 0 and pair 48 share opening 0 and, with a
        // deterministic search at fixed nodes, produce a byte-identical game.
        // Keying on the pair index would hide exactly the thing being measured.
        var a = LogAudit.audit(List.of(
                "0:0\t4\tWHITE_WINS/BLACK_WINS",
                "0:48\t4\tWHITE_WINS/BLACK_WINS",
                "0:96\t4\tWHITE_WINS/BLACK_WINS"));
        assertEquals(3, a.recorded());
        assertEquals(1, a.distinct());
        assertEquals(3.0, a.replication(), 1e-9);
        assertFalse(a.clean());
    }

    @Test @DisplayName("Same opening, genuinely different game, counts as evidence")
    void differentResultSameOpeningIsDistinct() {
        // After the Openings.lineFor fix, pair 48 extends the book line, so the
        // game really does differ. That must NOT be reported as replay.
        var a = LogAudit.audit(List.of(
                "0:0\t4\tWHITE_WINS/BLACK_WINS",
                "0:48\t2\tDRAW_FIFTY_MOVE/WHITE_WINS"));
        assertEquals(2, a.distinct());
        assertTrue(a.clean());
    }

    @Test @DisplayName("Keys without an opening index still audit")
    void handlesCurrentKeyFormat() {
        // Job.key() is now the pair index alone.
        var a = LogAudit.audit(List.of("0\t2\tA/B", "1\t3\tC/D"));
        assertEquals(2, a.recorded());
        assertEquals(2, a.distinct());
    }

    @Test @DisplayName("Torn and blank lines are skipped, not counted")
    void skipsMalformed() {
        var a = LogAudit.audit(List.of(
                "0:0\t2\tA/B",
                "",
                "0:1\t3",              // torn final line after a hard kill
                "garbage"));
        assertEquals(1, a.recorded());
    }

    @Test @DisplayName("An empty log is not clean, because it is not evidence either")
    void emptyIsNotClean() {
        assertFalse(LogAudit.audit(List.<String>of()).clean());
    }
}
