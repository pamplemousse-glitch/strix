package strix.harness;

import strix.core.*;

import java.io.IOException;

/**
 * Plays one game between two UCI engines and adjudicates it.
 *
 * The harness owns the board and decides when the game is over, rather than
 * trusting either engine to say so. An engine that mis-detects a draw, or plays
 * an illegal move, is exactly the failure this is meant to catch, so it cannot
 * also be the referee.
 */
public final class Game {

    public record Outcome(GameResult result, int plies, String moves, String note) {}

    public static Outcome play(UciEngine white, UciEngine black, String openingMoves,
                               long movetimeMillis, int maxPlies) throws IOException {
        Board board = Fen.parse(Fen.START);
        StringBuilder moves = new StringBuilder();

        for (String u : openingMoves.trim().split("\\s+")) {
            if (u.isEmpty()) continue;
            int m = resolve(board, u);
            if (m == Move.NONE) throw new IllegalArgumentException("illegal opening move " + u);
            board.make(m);
            moves.append(u).append(' ');
        }

        white.newGame();
        black.newGame();

        int plies = 0;
        while (plies < maxPlies) {
            GameResult r = GameResult.of(board);
            if (r.isOver()) return new Outcome(r, plies, moves.toString().trim(), "");

            UciEngine toMove = (board.sideToMove == Piece.WHITE) ? white : black;
            String uci = toMove.bestMove(moves.toString().trim(), movetimeMillis);

            int move = resolve(board, uci);
            if (move == Move.NONE) {
                // An illegal move forfeits. Silently ignoring it would let a broken
                // patch score points it did not earn.
                GameResult forfeit = (board.sideToMove == Piece.WHITE)
                        ? GameResult.BLACK_WINS : GameResult.WHITE_WINS;
                return new Outcome(forfeit, plies, moves.toString().trim(),
                        "illegal move from " + toMove.name + ": " + uci);
            }
            board.make(move);
            moves.append(uci).append(' ');
            plies++;
        }
        return new Outcome(GameResult.DRAW_FIFTY_MOVE, plies, moves.toString().trim(),
                "hit the " + maxPlies + " ply cap");
    }

    private static int resolve(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals(uci)) return m[i];
        return Move.NONE;
    }
}
