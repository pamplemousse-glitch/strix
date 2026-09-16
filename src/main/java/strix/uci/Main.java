package strix.uci;

import strix.core.*;
import strix.eval.Psqt;
import strix.search.Search;
import strix.search.SearchLimits;
import strix.search.Ordering;
import strix.search.TranspositionTable;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The UCI loop. Plain text over stdin and stdout, one command per line.
 *
 * The GUI owns the game. It resends the full move list on every `position`
 * command, so the engine rebuilds from scratch and never has to trust its own
 * accumulated state.
 *
 * Search runs on a worker thread so that `stop` and `isready` stay responsive
 * while thinking.
 */
public final class Main {

    private static final String NAME = "Strix 0.1.0";
    private static final String AUTHOR = "Antoine Wiley";

    private Board board = Fen.parse(Fen.START);
    private final Search search = new Search(new Psqt());
    private final TranspositionTable tt = new TranspositionTable(64);
    private Thread worker;

    public static void main(String[] args) throws Exception {
        new Main().run();
    }

    private void run() throws Exception {
        search.tt = tt;
        search.ordering = new Ordering();
        search.setListener(this::info);
        var in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] tok = line.split("\\s+");
            switch (tok[0]) {
                case "uci" -> {
                    out("id name " + NAME);
                    out("id author " + AUTHOR);
                    out("uciok");
                }
                case "isready" -> { join(); out("readyok"); }
                case "ucinewgame" -> { join(); board = Fen.parse(Fen.START); tt.clear(); }
                case "position" -> { join(); position(tok); }
                case "go" -> go(tok);
                case "stop" -> { search.stop(); join(); }
                case "quit" -> { search.stop(); join(); return; }
                default -> { /* UCI says ignore unknown commands */ }
            }
        }
    }

    private void position(String[] tok) {
        int i = 1;
        if (i < tok.length && tok[i].equals("startpos")) {
            board = Fen.parse(Fen.START);
            i++;
        } else if (i < tok.length && tok[i].equals("fen")) {
            StringBuilder fen = new StringBuilder();
            i++;
            while (i < tok.length && !tok[i].equals("moves")) fen.append(tok[i++]).append(' ');
            board = Fen.parse(fen.toString().trim());
        }
        if (i < tok.length && tok[i].equals("moves")) {
            i++;
            for (; i < tok.length; i++) {
                int move = parse(tok[i]);
                if (move == Move.NONE) break;
                board.make(move);
            }
        }
    }

    /** Resolve a UCI move string by matching it against the legal moves. */
    private int parse(String uci) {
        int[] moves = new int[MoveGen.MAX_MOVES];
        int[] scratch = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(board, moves, scratch);
        for (int i = 0; i < n; i++) {
            if (Move.toUci(moves[i]).equals(uci)) return moves[i];
        }
        return Move.NONE;
    }

    private void go(String[] tok) {
        join();
        SearchLimits limits = new SearchLimits();
        for (int i = 1; i < tok.length; i++) {
            switch (tok[i]) {
                case "wtime" -> limits.wtime = Long.parseLong(tok[++i]);
                case "btime" -> limits.btime = Long.parseLong(tok[++i]);
                case "winc" -> limits.winc = Long.parseLong(tok[++i]);
                case "binc" -> limits.binc = Long.parseLong(tok[++i]);
                case "movetime" -> limits.movetime = Long.parseLong(tok[++i]);
                case "depth" -> limits.depth = Integer.parseInt(tok[++i]);
                case "infinite" -> limits.infinite = true;
                default -> { }
            }
        }
        worker = new Thread(() -> {
            search.think(board, limits);
            int best = search.bestMove;
            if (best == Move.NONE) {
                int[] m = new int[MoveGen.MAX_MOVES];
                int n = MoveGen.generateLegal(board, m, new int[MoveGen.MAX_MOVES]);
                best = (n > 0) ? m[0] : Move.NONE;
            }
            out("bestmove " + (best == Move.NONE ? "0000" : Move.toUci(best)));
        }, "strix-search");
        worker.start();
    }

    private void info(int depth, int score, long nodes, long millis, int bestMove) {
        long nps = millis > 0 ? nodes * 1000 / millis : 0;
        out("info depth " + depth + " score " + uciScore(score)
                + " nodes " + nodes + " nps " + nps + " time " + millis
                + " pv " + Move.toUci(bestMove));
    }

    /** UCI reports forced mates as a move count, not a centipawn score. */
    private static String uciScore(int score) {
        if (Math.abs(score) > Search.MATE - 64) {
            int plies = Search.MATE - Math.abs(score);
            int moves = (plies + 1) / 2;
            return "mate " + (score > 0 ? moves : -moves);
        }
        return "cp " + score;
    }

    private void join() {
        if (worker != null) {
            try { worker.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            worker = null;
        }
    }

    private static void out(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
