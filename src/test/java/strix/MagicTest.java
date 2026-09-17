package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.Attacks;
import strix.core.Magic;
import strix.core.Square;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Magic bitboards are an OPTIMIZATION. They must return exactly what the ray
 * loops return, for every square and every occupancy, or they are a bug.
 *
 * Random FULL-BOARD occupancies matter here, not just subsets of the relevant
 * mask. The tables were built from mask subsets, so testing only those would be
 * circular and would never catch a wrong mask. Bits outside the mask must be
 * ignored, and only a full-board occupancy proves that.
 */
class MagicTest {

    @Test @DisplayName("Rook: magic equals ray loops on random full-board occupancies")
    void rookMatches() {
        SplittableRandom rng = new SplittableRandom(12345);
        for (int sq = 0; sq < 64; sq++) {
            for (int trial = 0; trial < 2_000; trial++) {
                long occupied = rng.nextLong() & rng.nextLong();   // realistic density
                final int s = sq;
                assertEquals(Attacks.rook(sq, occupied), Magic.rook(sq, occupied),
                        () -> "rook mismatch on " + Square.name(s));
            }
        }
    }

    @Test @DisplayName("Bishop: magic equals ray loops on random full-board occupancies")
    void bishopMatches() {
        SplittableRandom rng = new SplittableRandom(67890);
        for (int sq = 0; sq < 64; sq++) {
            for (int trial = 0; trial < 2_000; trial++) {
                long occupied = rng.nextLong() & rng.nextLong();
                final int s = sq;
                assertEquals(Attacks.bishop(sq, occupied), Magic.bishop(sq, occupied),
                        () -> "bishop mismatch on " + Square.name(s));
            }
        }
    }

    @Test @DisplayName("Empty and full boards, the two extremes")
    void extremes() {
        for (int sq = 0; sq < 64; sq++) {
            assertEquals(Attacks.rook(sq, 0L), Magic.rook(sq, 0L));
            assertEquals(Attacks.bishop(sq, 0L), Magic.bishop(sq, 0L));
            assertEquals(Attacks.rook(sq, ~0L), Magic.rook(sq, ~0L));
            assertEquals(Attacks.bishop(sq, ~0L), Magic.bishop(sq, ~0L));
        }
    }
}
