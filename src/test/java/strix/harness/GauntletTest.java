package strix.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GauntletTest {

    @Test @DisplayName("An even score is an even match")
    void evenScoreIsZeroElo() {
        assertEquals(0.0, new Gauntlet.Result(50, 0, 50).elo(), 0.01);
        assertEquals(0.0, new Gauntlet.Result(0, 100, 0).elo(), 0.01);
        assertEquals(0.5, new Gauntlet.Result(25, 50, 25).score(), 1e-9);
    }

    @Test @DisplayName("The Elo curve matches its textbook values")
    void knownEloPoints() {
        // 75% is the canonical +191 Elo; 64% is about +100.
        assertEquals(191.0, new Gauntlet.Result(75, 0, 25).elo(), 1.5);
        assertEquals(100.0, new Gauntlet.Result(64, 0, 36).elo(), 5.0);
        assertEquals(-191.0, new Gauntlet.Result(25, 0, 75).elo(), 1.5);
    }

    @Test @DisplayName("A clean sweep saturates instead of diverging")
    void saturates() {
        // log10(1/1 - 1) is negative infinity, so the unguarded formula returns
        // an infinity and every downstream average becomes NaN.
        assertEquals(800, new Gauntlet.Result(10, 0, 0).elo(), 1e-9);
        assertEquals(-800, new Gauntlet.Result(0, 0, 10).elo(), 1e-9);
        assertTrue(Double.isFinite(new Gauntlet.Result(10, 0, 0).margin()));
    }

    @Test @DisplayName("More games narrow the interval, and draws narrow it faster")
    void marginBehaves() {
        double few = new Gauntlet.Result(25, 0, 25).margin();
        double many = new Gauntlet.Result(250, 0, 250).margin();
        assertTrue(many < few, "more games must narrow the interval: " + many + " vs " + few);

        // A drawish result has less per-game variance than a decisive one at the
        // same score, so the same number of games says more.
        double decisive = new Gauntlet.Result(50, 0, 50).margin();
        double drawish = new Gauntlet.Result(10, 80, 10).margin();
        assertTrue(drawish < decisive,
                "draws should tighten the interval: " + drawish + " vs " + decisive);
    }

    @Test @DisplayName("Too few games admits it rather than inventing precision")
    void tinySamples() {
        assertEquals(800, new Gauntlet.Result(1, 0, 0).margin(), 1e-9);
        assertEquals(0, new Gauntlet.Result(0, 0, 0).games());
    }
}
