package strix.harness;

import strix.core.Board;
import strix.core.Fen;
import strix.core.GameResult;
import strix.core.Move;
import strix.core.MoveGen;

import java.util.SplittableRandom;

import java.util.List;

/**
 * Starting positions for test matches, as UCI move sequences.
 *
 * Every game starting from the initial position would produce nearly the same
 * game every time, so a thousand games would carry one game's worth of
 * information.
 *
 * These are deliberately varied and deliberately NOT all balanced. Draws carry
 * almost no information about which engine is stronger, and balanced books between
 * close engines produce very high draw rates: Stefan Pohl measured 84.1% draws with
 * a balanced book versus 52.8% with his Unbalanced Human Openings set, on the same
 * pairing. Halving the draw rate roughly halves the games needed for a verdict.
 *
 * The unfairness is cancelled by playing every position TWICE with colours
 * reversed, which is the same pairing the pentanomial model needs anyway.
 *
 * These are real opening lines rather than a generated set, and 4 to 6 plies deep,
 * which is enough to diverge without dictating the game.
 */
public final class Openings {

    private Openings() {}

    public static final List<String> BOOK = List.of(
            // Open games
            "e2e4 e7e5 g1f3 b8c6 f1b5",           // Ruy Lopez
            "e2e4 e7e5 g1f3 b8c6 f1c4",           // Italian
            "e2e4 e7e5 g1f3 b8c6 d2d4",           // Scotch
            "e2e4 e7e5 g1f3 g8f6",                // Petrov
            "e2e4 e7e5 b1c3 g8f6",                // Vienna
            "e2e4 e7e5 f2f4",                     // King's Gambit
            "e2e4 e7e5 g1f3 b8c6 b1c3 g8f6",      // Four Knights
            // Sicilian
            "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4",      // Open Sicilian
            "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4",      // Sicilian, Nc6
            "e2e4 c7c5 g1f3 e7e6 d2d4 c5d4",      // Taimanov
            "e2e4 c7c5 b1c3 b8c6 g2g3",           // Closed Sicilian
            "e2e4 c7c5 c2c3 d7d5",                // Alapin
            // Semi-open
            "e2e4 e7e6 d2d4 d7d5 b1c3",           // French
            "e2e4 e7e6 d2d4 d7d5 e4e5",           // French Advance
            "e2e4 c7c6 d2d4 d7d5 b1c3",           // Caro-Kann
            "e2e4 c7c6 d2d4 d7d5 e4e5",           // Caro Advance
            "e2e4 d7d5 e4d5 d8d5 b1c3",           // Scandinavian
            "e2e4 g8f6 e4e5 f6d5",                // Alekhine
            "e2e4 d7d6 d2d4 g8f6 b1c3",           // Pirc
            "e2e4 g7g6 d2d4 f8g7 b1c3",           // Modern
            // Queen's pawn
            "d2d4 d7d5 c2c4 e7e6 b1c3",           // QGD
            "d2d4 d7d5 c2c4 c7c6 g1f3",           // Slav
            "d2d4 d7d5 c2c4 d5c4",                // QGA
            "d2d4 d7d5 c1f4",                     // London
            "d2d4 d7d5 g1f3 g8f6 c2c4 e7e6",      // QGD, Nf3
            // Indian defences
            "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4",      // Nimzo-Indian
            "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7",      // King's Indian
            "d2d4 g8f6 c2c4 g7g6 b1c3 d7d5",      // Gruenfeld
            "d2d4 g8f6 c2c4 e7e6 g1f3 b7b6",      // Queen's Indian
            "d2d4 g8f6 c2c4 c7c5 d4d5",           // Benoni
            "d2d4 g8f6 c2c4 e7e5",                // Budapest
            "d2d4 f7f5 g2g3",                     // Dutch
            // Flank
            "c2c4 e7e5 b1c3 g8f6",                // English
            "c2c4 g8f6 b1c3 e7e6",                // English, Indian
            "g1f3 d7d5 g2g3 g8f6",                // Reti
            "b2b3 e7e5 c1b2",                     // Larsen
            "g2g3 d7d5 f1g2 g8f6",                // King's fianchetto
            "d2d4 d7d5 e2e3 g8f6 f1d3",           // Colle
            "e2e4 b8c6 d2d4 d7d5",                // Nimzowitsch
            "d2d4 e7e6 c2c4 f8b4",                // Keres
            "e2e4 e7e5 g1f3 d7d6",                // Philidor
            "e2e4 c7c5 g1f3 d7d6 f1b5",           // Moscow
            "d2d4 g8f6 g1f3 e7e6 c1g5",           // Torre
            "e2e4 e7e5 f1c4 g8f6",                // Bishop's Opening
            "c2c4 c7c5 g1f3 g8f6",                // Symmetrical English
            "d2d4 c7c5 d4d5 e7e6",                // Benoni, early
            "e2e4 d7d5 e4d5 g8f6",                // Scandinavian, Nf6
            "g1f3 g8f6 c2c4 c7c5"                 // Reti, symmetrical
    );

    public static int size() { return BOOK.size(); }
    public static String get(int i) { return BOOK.get(Math.floorMod(i, BOOK.size())); }

    /**
     * How many extra plies a pair on cycle {@code c} gets. Cycle 0 is the plain book.
     *
     * Two or four, alternating, and deliberately not more. Random plies unbalance
     * the position, and while the colour swap inside a pair cancels that on
     * average, a wildly lost position measures how well both engines convert a
     * win rather than which is stronger. Two plies already give roughly 30x30
     * continuations per book line, far more diversity than any run needs.
     */
    private static int extraPlies(int cycle) {
        return cycle == 0 ? 0 : (cycle % 2 == 1 ? 2 : 4);    // 0, 2, 4, 2, 4, ...
    }

    /**
     * The opening line for a pair index, diversified past the end of the book.
     *
     * <h2>Why this exists</h2>
     * The harness asked for {@code p % size()} and the search is deterministic at
     * a fixed node count, so pair 0 and pair 48 played the <em>identical</em>
     * game. Every run past 48 pairs was replaying the same 48 games and recording
     * each replay as a fresh observation.
     *
     * Measured in the shipped logs: {@code texel-sprt.tsv} has 280 pairs and 48
     * distinct (opening, bucket, result) triples. {@code hash-tight.tsv} has 385
     * pairs and 48. Not "mostly similar": byte-identical, every time.
     *
     * The LLR is linear in the bucket counts, so replicating the sample k times
     * multiplies the LLR by k while adding no information. The test then crosses
     * a bound with probability approaching 1 for any target past the book size,
     * in whichever direction those 48 games happen to lean. The alpha and beta
     * guarantees are void past that point, not merely weakened.
     *
     * <h2>The fix, and why not simply a bigger book</h2>
     * A bigger book is the better answer and is not mutually exclusive with this
     * one, but it only moves the ceiling: 500 openings still caps a run at 500
     * pairs. This removes the ceiling.
     *
     * Each cycle past the first extends the book line by 2, 4 or 6 uniformly
     * random legal plies, seeded from the pair index. Seeded from the pair index
     * is the load-bearing part: two workers computing the same pair still produce
     * the identical result, which is what ADR 0011 and the cluster's duplicate
     * dropping both depend on.
     *
     * Random plies unbalance the position, and that is fine here for the reason
     * pairs exist at all: both engines play it from both sides, so the imbalance
     * lands on each of them exactly once and cancels in the pair score.
     */
    public static String lineFor(int pairIndex) {
        String base = get(pairIndex);
        int cycle = Math.floorDiv(pairIndex, BOOK.size());
        int extra = extraPlies(cycle);
        if (extra == 0) return base;

        Board board = Fen.parse(Fen.START);
        StringBuilder line = new StringBuilder(base);
        for (String u : base.trim().split("\\s+")) {
            int m = resolve(board, u);
            if (m == Move.NONE) return base;      // book line is broken; leave it alone
            board.make(m);
        }

        // Mixed with a 64-bit odd constant so neighbouring pair indices do not
        // produce neighbouring move choices.
        SplittableRandom rng = new SplittableRandom(0x9E3779B97F4A7C15L * (pairIndex + 1));
        int[] moves = new int[MoveGen.MAX_MOVES];
        int[] scratch = new int[MoveGen.MAX_MOVES];

        for (int i = 0; i < extra; i++) {
            int n = MoveGen.generateLegal(board, moves, scratch);
            if (n == 0) break;                    // mated or stalemated; stop here
            int chosen = moves[rng.nextInt(n)];
            board.make(chosen);
            line.append(' ').append(Move.toUci(chosen));
            if (GameResult.of(board).isOver()) break;
        }
        return line.toString();
    }

    private static int resolve(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals(uci)) return m[i];
        return Move.NONE;
    }
}
