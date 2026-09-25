package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.*;
import strix.nnue.Accumulator;
import strix.nnue.Network;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The accumulator invariant, the same shape as the Zobrist one: a value
 * maintained by deltas must never drift from the value computed from scratch.
 *
 * Drift here does not crash. It produces a slightly wrong evaluation that gets
 * slightly wronger, and the engine plays badly for reasons that look nothing
 * like a neural network bug.
 */
class NnueTest {

    private static final Network NET = Network.random(0xC0FFEE);

    private static final String[] POSITIONS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            // Kiwipete: castling both sides, pins, captures everywhere
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            // en passant available
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            // promotions, including under check
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
    };

    @Test @DisplayName("From-scratch accumulator equals the network's own evaluation")
    void refreshMatchesEvaluate() {
        for (String fen : POSITIONS) {
            Board b = Fen.parse(fen);
            Accumulator acc = new Accumulator(NET);
            acc.refresh(b);
            assertEquals(NET.evaluate(b), acc.evaluate(b.sideToMove),
                    "refresh disagrees with evaluate on " + fen);
        }
    }

    @Test @DisplayName("Incremental accumulator never drifts, through make and unmake")
    void incrementalNeverDrifts() {
        for (String fen : POSITIONS) {
            Board board = Fen.parse(fen);
            Accumulator acc = new Accumulator(NET);
            acc.refresh(board);
            walk(board, acc, 3, fen);
        }
    }

    private void walk(Board board, Accumulator acc, int depth, String fen) {
        if (depth == 0) return;
        int[] moves = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(board, moves, new int[MoveGen.MAX_MOVES]);

        for (int i = 0; i < n; i++) {
            int move = moves[i];
            float[] before = acc.snapshot(Piece.WHITE);

            acc.make(board, move);          // before make: the board still shows the victim
            board.make(move);

            Accumulator fresh = new Accumulator(NET);
            fresh.refresh(board);
            assertArrayEquals(fresh.snapshot(Piece.WHITE), acc.snapshot(Piece.WHITE), 1e-3f,
                    () -> "drift after " + Move.toUci(move) + " in " + fen);
            assertArrayEquals(fresh.snapshot(Piece.BLACK), acc.snapshot(Piece.BLACK), 1e-3f,
                    () -> "drift (black view) after " + Move.toUci(move) + " in " + fen);

            walk(board, acc, depth - 1, fen);

            board.unmake(move);
            acc.unmake();                   // a pop, not a reverse delta
            assertArrayEquals(before, acc.snapshot(Piece.WHITE), 1e-3f,
                    () -> "unmake did not restore state after " + Move.toUci(move));
        }
    }

    @Test @DisplayName("Castling moves two pieces and both must be accounted for")
    void castlingUpdatesTheRookToo() {
        Board b = Fen.parse("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        Accumulator acc = new Accumulator(NET);
        acc.refresh(b);

        int castle = find(b, "e1g1");
        acc.make(b, castle);
        b.make(castle);

        Accumulator fresh = new Accumulator(NET);
        fresh.refresh(b);
        assertArrayEquals(fresh.snapshot(Piece.WHITE), acc.snapshot(Piece.WHITE), 1e-3f,
                "castling did not move the rook in the accumulator");
    }

    @Test @DisplayName("Promotion swaps the piece type, not just the square")
    void promotionChangesPieceType() {
        Board b = Fen.parse("8/P7/8/8/8/8/8/K6k w - - 0 1");
        Accumulator acc = new Accumulator(NET);
        acc.refresh(b);

        int promo = find(b, "a7a8q");
        acc.make(b, promo);
        b.make(promo);

        Accumulator fresh = new Accumulator(NET);
        fresh.refresh(b);
        assertArrayEquals(fresh.snapshot(Piece.WHITE), acc.snapshot(Piece.WHITE), 1e-3f,
                "a promoted pawn must come off as a pawn and go back as a queen");
    }

    @Test @DisplayName("Quantized output tracks the float network within tolerance")
    void quantizationIsCloseEnough() {
        var q = strix.nnue.Quantized.from(NET);
        int worst = 0;
        for (String fen : POSITIONS) {
            Board b = Fen.parse(fen);
            int f = NET.evaluate(b);
            int i = q.evaluate(b);
            worst = Math.max(worst, Math.abs(f - i));
        }
        // Quantisation is lossy by construction, unlike alpha-beta or magic
        // bitboards, so this is a tolerance rather than an equality. A few
        // centipawns is far below the noise the search itself introduces.
        System.out.println("  worst float-vs-quantized gap: " + worst + " cp");
        assertTrue(worst <= 10, "quantization drifted " + worst + " cp from the float network");
    }

    @Test @DisplayName("Quantized and float agree across many random positions")
    void quantizationHoldsUnderRandomWalks() {
        var q = strix.nnue.Quantized.from(NET);
        var rng = new java.util.SplittableRandom(99);
        int worst = 0;
        for (int game = 0; game < 40; game++) {
            Board b = Fen.parse(Fen.START);
            for (int ply = 0; ply < 40; ply++) {
                int[] moves = new int[MoveGen.MAX_MOVES];
                int n = MoveGen.generateLegal(b, moves, new int[MoveGen.MAX_MOVES]);
                if (n == 0) break;
                b.make(moves[rng.nextInt(n)]);
                worst = Math.max(worst, Math.abs(NET.evaluate(b) - q.evaluate(b)));
            }
        }
        System.out.println("  worst gap over random walks: " + worst + " cp");
        assertTrue(worst <= 15, "quantization drifted " + worst + " cp on a random walk");
    }

    private static int find(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals(uci)) return m[i];
        throw new IllegalStateException("no legal move " + uci);
    }

    @Test @DisplayName("The incremental invariant survives past the old 96-ply cap")
    void deepGamesDoNotDesync() {
        // The stack was fixed at 96 and the push was guarded by
        // `if (ply + 1 < MAX_PLY)`. At the cap the deltas were applied IN PLACE
        // at the current ply, so the matching unmake decremented past the state
        // it should have restored and the accumulator stayed one ply out of
        // step for the entire unwind. Measured before the fix: 0 mismatches at
        // 95 plies, 79 at 96.
        Board board = Fen.parse(Fen.START);
        Accumulator acc = new Accumulator(NET);
        acc.refresh(board);

        java.util.ArrayDeque<Integer> played = new java.util.ArrayDeque<>();
        java.util.SplittableRandom rng = new java.util.SplittableRandom(99);
        int[] moves = new int[MoveGen.MAX_MOVES];
        int[] scratch = new int[MoveGen.MAX_MOVES];

        for (int ply = 0; ply < 130; ply++) {
            int n = MoveGen.generateLegal(board, moves, scratch);
            if (n == 0) break;
            int m = moves[rng.nextInt(n)];
            acc.make(board, m);
            board.make(m);
            played.push(m);
        }
        assertTrue(played.size() > 96, "need to get past the old cap to test it");

        while (!played.isEmpty()) {
            int m = played.pop();
            board.unmake(m);
            acc.unmake();

            Accumulator fresh = new Accumulator(NET);
            fresh.refresh(board);
            assertEquals(fresh.evaluate(board.sideToMove), acc.evaluate(board.sideToMove),
                    "incremental drifted from scratch at ply " + played.size());
        }
    }

    /**
     * Colour-swap the pieces, flip the board, flip the side to move: the
     * evaluation must not change.
     *
     * This is the only NNUE test here that does not go through
     * Network.featureIndex to decide what it expects. Every other test compares
     * Network, Accumulator and Quantized against each other, and all three call
     * featureIndex, so a perspective or mirroring bug inside it would make all
     * of them agree and all of them pass.
     *
     * The sign is the subtle part and it is easy to get backwards. The eval is
     * relative to the side to move. After the mirror, the new side to move holds
     * exactly what the old one held, so the value is the SAME, not negated.
     */
    @Test @DisplayName("Colour-swapped mirror evaluates identically, independent of featureIndex")
    void mirroredPositionEvaluatesTheSame() {
        for (String fen : POSITIONS) {
            int direct = NET.evaluate(Fen.parse(fen));
            int mirrored = NET.evaluate(Fen.parse(mirror(fen)));
            assertEquals(direct, mirrored, 1,
                    "perspective/mirror asymmetry on " + fen);
        }
    }

    /** Vertical flip plus colour swap, done on the FEN so the test shares no code with the net. */
    private static String mirror(String fen) {
        String[] p = fen.trim().split("\\s+");
        String[] ranks = p[0].split("/");
        StringBuilder board = new StringBuilder();
        for (int i = ranks.length - 1; i >= 0; i--) {
            for (char c : ranks[i].toCharArray()) board.append(swapCase(c));
            if (i > 0) board.append('/');
        }
        StringBuilder castling = new StringBuilder();
        for (char c : p[2].toCharArray()) castling.append(swapCase(c));
        String ep = p[3].equals("-") ? "-"
                : "" + p[3].charAt(0) + (char) ('0' + (9 - (p[3].charAt(1) - '0')));
        return board + " " + (p[1].equals("w") ? "b" : "w") + " "
                + (castling.length() == 0 ? "-" : castling.toString()) + " " + ep + " 0 1";
    }

    private static char swapCase(char c) {
        if (!Character.isLetter(c)) return c;
        return Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c);
    }
}