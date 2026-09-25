package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.*;

import static org.junit.jupiter.api.Assertions.*;

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
     * An en passant square that nobody can use is not part of the position.
     *
     * 1.Nf3 e5 2.e4 and 1.e4 e5 2.Nf3 reach identical piece placement. The second
     * ends on a double pawn push, so the naive rule records an ep square of e3 and
     * gives the two different hashes.
     *
     * But no black pawn stands on d4 or f4, so that capture does not exist, and
     * under FIDE rules these are the same position. This test used to assert the
     * naive behaviour with a note that the refinement "waits for Stage 3". It
     * arrived: Board.make now claims an ep square only when an enemy pawn is
     * placed to take it.
     *
     * The refinement is not only an optimization. Hashing a right that cannot be
     * exercised splits one position into two keys, and a threefold repetition
     * spanning the split is never counted.
     */
    @Test @DisplayName("An unusable en passant square does not change identity")
    void unusableEnPassantIsNotPartOfThePosition() {
        Board viaPush = play("g1f3", "e7e5", "e2e4");
        Board viaKnight = play("e2e4", "e7e5", "g1f3");
        assertEquals(viaKnight.hash, viaPush.hash, "no black pawn can take on e3");
        assertEquals(Square.NONE, viaPush.epSquare);
        assertEquals(Zobrist.compute(viaPush), viaPush.hash);
        assertEquals(Zobrist.compute(viaKnight), viaKnight.hash);
    }

    /**
     * A usable one still does, which is the half that must not regress.
     *
     * After 1.e4 d5 2.e5 f5 the black f-pawn lands beside the white e5 pawn, so
     * exf6 e.p. is real. Drop the ep component here and the engine would treat a
     * position where that capture is available as identical to one where it has
     * expired, and take a transposition-table score for the wrong position.
     */
    @Test @DisplayName("A capturable en passant square does change identity")
    void capturableEnPassantAffectsIdentity() {
        Board withEp = play("e2e4", "d7d5", "e4e5", "f7f5");
        assertNotEquals(Square.NONE, withEp.epSquare, "exf6 e.p. is available");
        assertEquals(Zobrist.compute(withEp), withEp.hash);

        // Same pieces, but reached so that the capture has already expired.
        Board expired = play("e2e4", "d7d5", "e4e5", "f7f6", "g1f3", "f6f5", "f3g1");
        assertEquals(Square.NONE, expired.epSquare);
        assertNotEquals(withEp.hash, expired.hash, "one allows exf6 e.p., the other does not");
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
