package strix.harness;

import strix.core.*;

import java.io.IOException;

/**
 * Plays one game between two UCI engines and adjudicates it.
 *
 * The harness owns the board and decides when the game is over rather than
 * trusting either engine to say so. An engine that mis-detects a draw or plays an
 * illegal move is exactly the failure this exists to catch, so it cannot also be
 * the referee.
 *
 * <h2>Adjudication</h2>
 * Games that are already decided end early, which saves a great deal of time. The
 * thresholds are Fishtest's and they are deliberately conservative:
 *
 * <ul>
 *   <li><b>Resign</b> after 3 consecutive moves at 600 centipawns. Six pawns down.</li>
 *   <li><b>Draw</b> only after move 34, and only after 8 consecutive moves within
 *       20 centipawns of zero.</li>
 * </ul>
 *
 * Both are TWOSIDED: both engines must agree. That is the safeguard against one
 * engine's broken evaluation throwing away a game it was actually fine in, and it
 * means a genuinely complex game, where the engines disagree, keeps playing.
 */
public final class Game {

    public record Outcome(GameResult result, int plies, String moves, String note) {}

    public static final int RESIGN_SCORE = 600;
    public static final int RESIGN_MOVES = 3;
    public static final int DRAW_AFTER_MOVE = 34;
    public static final int DRAW_SCORE = 20;
    public static final int DRAW_MOVES = 8;

    public static Outcome play(UciEngine white, UciEngine black, String openingMoves,
                               String goArgs, long timeoutMillis, int maxPlies) throws IOException {
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

        int resignStreak = 0, drawStreak = 0, resignLoser = -1;
        int plies = 0;

        while (plies < maxPlies) {
            GameResult r = GameResult.of(board);
            if (r.isOver()) return new Outcome(r, plies, moves.toString().trim(), "");

            int mover = board.sideToMove;
            UciEngine toMove = (mover == Piece.WHITE) ? white : black;
            UciEngine.Reply reply = toMove.bestMove(moves.toString().trim(), goArgs, timeoutMillis);

            int move = resolve(board, reply.move());
            if (move == Move.NONE) {
                GameResult forfeit = (mover == Piece.WHITE) ? GameResult.BLACK_WINS : GameResult.WHITE_WINS;
                return new Outcome(forfeit, plies, moves.toString().trim(),
                        "illegal move from " + toMove.name + ": " + reply.move());
            }
            board.make(move);
            moves.append(reply.move()).append(' ');
            plies++;

            if (!reply.hasScore()) { resignStreak = 0; drawStreak = 0; continue; }

            // Scores are from the mover's point of view. Convert to White's.
            int white_cp = (mover == Piece.WHITE) ? reply.scoreCp() : -reply.scoreCp();

            if (Math.abs(white_cp) >= RESIGN_SCORE) {
                int loser = white_cp > 0 ? Piece.BLACK : Piece.WHITE;
                if (loser == resignLoser) resignStreak++;
                else { resignLoser = loser; resignStreak = 1; }
                drawStreak = 0;
                if (resignStreak >= RESIGN_MOVES * 2) {   // both sides, N moves each
                    return new Outcome(loser == Piece.WHITE ? GameResult.BLACK_WINS : GameResult.WHITE_WINS,
                            plies, moves.toString().trim(), "adjudicated: resign");
                }
            } else if (Math.abs(white_cp) <= DRAW_SCORE && board.fullmove >= DRAW_AFTER_MOVE) {
                resignStreak = 0;
                if (++drawStreak >= DRAW_MOVES * 2) {
                    return new Outcome(GameResult.DRAW_FIFTY_MOVE, plies, moves.toString().trim(),
                            "adjudicated: draw");
                }
            } else {
                resignStreak = 0;
                drawStreak = 0;
            }
        }
        // The position after the LAST move was never tested, because the check
        // sits at the top of the loop and the loop exits on the ply count. A
        // game checkmated exactly on ply maxPlies was scored 0.5 instead of
        // 1/0, which moves the pair a whole bucket.
        GameResult last = GameResult.of(board);
        if (last.isOver()) {
            return new Outcome(last, plies, moves.toString().trim(),
                    "decided on the final allowed ply");
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
