package strix.core;

/** FEN parsing and emission. Tolerates a missing halfmove/fullmove pair. */
public final class Fen {

    public static final String START =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private Fen() {}

    public static Board parse(String fen) {
        Board b = new Board();
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) throw new IllegalArgumentException("bad FEN: " + fen);

        int rank = 7, file = 0;
        for (char c : parts[0].toCharArray()) {
            if (c == '/') {
                rank--;
                file = 0;
            } else if (Character.isDigit(c)) {
                file += c - '0';
            } else {
                int piece = Piece.fromChar(c);
                if (piece == Piece.NONE) throw new IllegalArgumentException("bad piece: " + c);
                b.put(piece, Square.of(file, rank));
                file++;
            }
        }

        b.sideToMove = parts[1].equals("w") ? Piece.WHITE : Piece.BLACK;

        b.castling = 0;
        if (parts[2].indexOf('K') >= 0) b.castling |= Board.CASTLE_WK;
        if (parts[2].indexOf('Q') >= 0) b.castling |= Board.CASTLE_WQ;
        if (parts[2].indexOf('k') >= 0) b.castling |= Board.CASTLE_BK;
        if (parts[2].indexOf('q') >= 0) b.castling |= Board.CASTLE_BQ;

        // Both fields are validated against the pieces actually on the board. A
        // FEN is input, and MoveGen trusts castling rights and the ep square
        // outright: an unchecked one lets it generate a castle with no rook or an
        // ep capture with no pawn. See Board.validatedCastling / validatedEpSquare.
        b.castling = b.validatedCastling(b.castling);
        b.epSquare = b.validatedEpSquare(
                parts[3].equals("-") ? Square.NONE : Square.fromName(parts[3]));
        b.halfmoveClock = parts.length > 4 ? Math.max(0, Integer.parseInt(parts[4])) : 0;
        b.fullmove = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        b.hash = Zobrist.compute(b);
        return b;
    }

    public static String emit(Board b) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int p = b.mailbox[Square.of(file, rank)];
                if (p == Piece.NONE) {
                    empty++;
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(Piece.toChar(p));
                }
            }
            if (empty > 0) sb.append(empty);
            if (rank > 0) sb.append('/');
        }
        sb.append(b.sideToMove == Piece.WHITE ? " w " : " b ");
        if (b.castling == 0) {
            sb.append('-');
        } else {
            if ((b.castling & Board.CASTLE_WK) != 0) sb.append('K');
            if ((b.castling & Board.CASTLE_WQ) != 0) sb.append('Q');
            if ((b.castling & Board.CASTLE_BK) != 0) sb.append('k');
            if ((b.castling & Board.CASTLE_BQ) != 0) sb.append('q');
        }
        sb.append(' ').append(b.epSquare == Square.NONE ? "-" : Square.name(b.epSquare));
        sb.append(' ').append(b.halfmoveClock).append(' ').append(b.fullmove);
        return sb.toString();
    }
}
