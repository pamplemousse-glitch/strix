package strix.core;

/** How a game ended, from White's point of view. */
public enum GameResult {
    ONGOING(null),
    WHITE_WINS("1-0"),
    BLACK_WINS("0-1"),
    DRAW_STALEMATE("1/2-1/2"),
    DRAW_REPETITION("1/2-1/2"),
    DRAW_FIFTY_MOVE("1/2-1/2"),
    DRAW_INSUFFICIENT("1/2-1/2");

    public final String pgn;

    GameResult(String pgn) { this.pgn = pgn; }

    public boolean isOver() { return this != ONGOING; }

    /** 1.0, 0.5 or 0.0 from White's point of view. */
    public double whiteScore() {
        return switch (this) {
            case WHITE_WINS -> 1.0;
            case BLACK_WINS -> 0.0;
            default -> 0.5;
        };
    }

    /**
     * Adjudicate the current position.
     *
     * Order matters. Checkmate and stalemate are checked first because they are
     * terminal regardless of any other condition: a mate delivered on the
     * hundredth halfmove is a win, not a fifty-move draw.
     */
    public static GameResult of(Board b) {
        int[] moves = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, moves, new int[MoveGen.MAX_MOVES]);
        if (n == 0) {
            if (!b.inCheck(b.sideToMove)) return DRAW_STALEMATE;
            return b.sideToMove == Piece.WHITE ? BLACK_WINS : WHITE_WINS;
        }
        if (b.isInsufficientMaterial()) return DRAW_INSUFFICIENT;
        if (b.isFiftyMoveDraw()) return DRAW_FIFTY_MOVE;
        if (b.isRepetition(3)) return DRAW_REPETITION;
        return ONGOING;
    }
}
