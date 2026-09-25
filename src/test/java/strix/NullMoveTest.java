package strix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import strix.core.*;
import strix.eval.Psqt;
import strix.search.Ordering;
import strix.search.Search;
import strix.search.TranspositionTable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Null move pruning forfeits a turn, which is illegal in chess, so every piece
 * of state it touches has to come back exactly.
 *
 * The failure mode is silent: an asymmetric unmake leaves the Zobrist key wrong,
 * the transposition table then answers with another position's score, and the
 * engine plays plausible moves from a corrupted table.
 */
class NullMoveTest {

    private static final String[] POSITIONS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "rnbqkbnr/pp2pppp/8/2ppP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "4k3/8/8/8/8/8/4P3/4K3 w - - 12 40",
    };

    @Test @DisplayName("Null move restores every byte of state")
    void nullMoveIsSymmetric() {
        for (String fen : POSITIONS) {
            Board b = Fen.parse(fen);
            String before = Fen.emit(b);
            long hashBefore = b.hash;

            b.makeNull();
            b.unmakeNull();

            assertEquals(before, Fen.emit(b), "FEN changed across a null move: " + fen);
            assertEquals(hashBefore, b.hash, "hash changed across a null move: " + fen);
            assertEquals(Zobrist.compute(b), b.hash, "hash desynced: " + fen);
        }
    }

    @Test @DisplayName("The nulled position hashes as a real position would")
    void nulledPositionHashesCorrectly() {
        // Not just "restored afterwards": the intermediate state has to be a
        // legitimate key, because that is what the TT is probed with.
        for (String fen : POSITIONS) {
            Board b = Fen.parse(fen);
            b.makeNull();
            assertEquals(Zobrist.compute(b), b.hash,
                    "nulled position's incremental hash is wrong: " + fen);
            b.unmakeNull();
        }
    }

    @Test @DisplayName("A null move clears en passant but not the fifty-move clock")
    void nullMoveStateRules() {
        // The right to capture en passant expires at once; leaving it set would
        // let the opponent take a pawn that had two turns to sit there.
        // After 1.e4 c5 2.e5 d5 the white e5 pawn really can take on d6, so
        // Fen.parse keeps the square. (A FEN naming an ep square nobody can use
        // has it stripped, which is what Board.validatedEpSquare is for.)
        Board b = Fen.parse("rnbqkbnr/pp2pppp/8/2ppP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3");
        assertEquals(Square.of(3, 5), b.epSquare, "exd6 e.p. is available");
        b.makeNull();
        assertEquals(Square.NONE, b.epSquare, "en passant must expire");
        b.unmakeNull();
        assertEquals(Square.of(3, 5), b.epSquare, "and must come back");

        // A null move is neither a capture nor a pawn move, so the clock runs on.
        Board c = Fen.parse("4k3/8/8/8/8/8/4P3/4K3 w - - 12 40");
        c.makeNull();
        assertEquals(13, Integer.parseInt(Fen.emit(c).split(" ")[4]), "clock must not reset");
        c.unmakeNull();
        assertEquals(12, Integer.parseInt(Fen.emit(c).split(" ")[4]));
    }

    @Test @DisplayName("Nulling twice unwinds in the right order")
    void nestedNullMoves() {
        Board b = Fen.parse(POSITIONS[1]);
        long h = b.hash;
        String fen = Fen.emit(b);
        b.makeNull();
        b.makeNull();
        assertEquals(Zobrist.compute(b), b.hash);
        b.unmakeNull();
        b.unmakeNull();
        assertEquals(h, b.hash);
        assertEquals(fen, Fen.emit(b));
    }

    @Test @DisplayName("The zugzwang guard fires exactly in pawn endings")
    void zugzwangGuard() {
        // King and pawns only: having to move is frequently a disadvantage, so
        // "I passed and I am still winning" stops implying "my real moves win"
        // and the pruning rule inverts.
        Board pawnsOnly = Fen.parse("8/5k2/8/5K2/5P2/8/8/8 w - - 0 1");
        assertFalse(pawnsOnly.hasNonPawnMaterial(Piece.WHITE));
        assertFalse(pawnsOnly.hasNonPawnMaterial(Piece.BLACK));

        Board withRook = Fen.parse("8/5k2/8/5K2/5P2/8/8/7R w - - 0 1");
        assertTrue(withRook.hasNonPawnMaterial(Piece.WHITE));
        assertFalse(withRook.hasNonPawnMaterial(Piece.BLACK));
    }

    @Test @DisplayName("Null move does not change the move found on tactical positions")
    void doesNotBreakTactics() {
        // Pruning is allowed to change the SCORE at the margins; it is not
        // allowed to miss a mate that a plain search finds.
        String[] mates = {
            "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1",
            "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4",
        };
        for (String fen : mates) {
            Search off = newSearch(false);
            int scoreOff = off.alphaBeta(Fen.parse(fen), 5);
            Search on = newSearch(true);
            int scoreOn = on.alphaBeta(Fen.parse(fen), 5);
            assertEquals(Integer.signum(scoreOff), Integer.signum(scoreOn),
                    "null move flipped the sign of the evaluation on " + fen);
        }
    }

    private static Search newSearch(boolean nullMove) {
        Search s = new Search(new Psqt());
        s.useNullMove = nullMove;
        s.ordering = new Ordering();
        s.tt = new TranspositionTable(16);
        return s;
    }
}
