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
}
