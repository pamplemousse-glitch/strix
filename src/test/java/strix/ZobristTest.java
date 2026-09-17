package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The incremental hash must always equal the from-scratch hash.
 *
 * Same shape as the invariant the NNUE accumulator will need in Stage 4: a value
 * maintained by deltas must never drift from the value computed directly. If it
 * ever does, the transposition table starts returning another position's score
 * and the engine plays nonsense for reasons that look nothing like hashing.
 */
class ZobristTest {

    private static final String[] POSITIONS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
    };

    @Test @DisplayName("Incremental hash equals from-scratch hash at every node")
    void hashNeverDrifts() {
        for (String fen : POSITIONS) {
            Board b = Fen.parse(fen);
            assertEquals(Zobrist.compute(b), b.hash, "wrong at the root: " + fen);
            walk(b, 4, fen);
        }
    }

    private void walk(Board b, int depth, String fen) {
        if (depth == 0) return;
        int[] moves = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, moves, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) {
            b.make(moves[i]);
            assertEquals(Zobrist.compute(b), b.hash,
                    () -> "drifted after " + Move.toUci(moves[0]) + " in " + fen);
            walk(b, depth - 1, fen);
            b.unmake(moves[i]);
            assertEquals(Zobrist.compute(b), b.hash,
                    () -> "drifted after UNMAKE in " + fen);
        }
    }

    @Test @DisplayName("Transpositions collide, different positions do not")
    void transpositionsMatch() {
        // 1.Nf3 Nf6 2.Nc3 Nc6 and 1.Nc3 Nc6 2.Nf3 Nf6 reach an identical position.
        Board a = play("g1f3", "g8f6", "b1c3", "b8c6");
        Board c = play("b1c3", "b8c6", "g1f3", "g8f6");
        assertEquals(a.hash, c.hash, "transposition should hash identically");

        Board d = play("g1f3", "g8f6", "b1c3", "b8a6");
        org.junit.jupiter.api.Assertions.assertNotEquals(a.hash, d.hash);
    }

    /**
     * Deliberately NOT a transposition, and a good illustration of why.
     *
     * 1.e4 e5 2.Nf3 and 1.Nf3 e5 2.e4 have identical piece placement, but in the
     * second White just played a double pawn push, so the en passant square is e3
     * and in the first it is empty. Different positions, correctly different hashes.
     *
     * Strong engines refine this by hashing the en passant square only when an
     * enemy pawn can actually capture there, which recovers these as real
     * transpositions. That is a measurable optimization and so it waits for Stage 3.
     */
    @Test @DisplayName("Same pieces, different en passant rights, different hash")
    void enPassantRightsAffectIdentity() {
        Board withEp = play("g1f3", "e7e5", "e2e4");
        Board withoutEp = play("e2e4", "e7e5", "g1f3");
        org.junit.jupiter.api.Assertions.assertNotEquals(withEp.hash, withoutEp.hash);
        assertEquals(Zobrist.compute(withEp), withEp.hash);
        assertEquals(Zobrist.compute(withoutEp), withoutEp.hash);
    }

    private static Board play(String... ucis) {
        Board b = Fen.parse(Fen.START);
        for (String u : ucis) {
            int[] m = new int[MoveGen.MAX_MOVES];
            int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
            for (int i = 0; i < n; i++) {
                if (Move.toUci(m[i]).equals(u)) { b.make(m[i]); break; }
            }
        }
        return b;
    }
}
