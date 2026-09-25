package strix.uci;

import strix.core.*;
import strix.eval.Material;
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
            // The UCI spec requires ignoring anything not understood, and a GUI
            // sends plenty. Without this, three ordinary malformed inputs killed
            // the process outright: "go wtime" (index past the end),
            // "position fen not-a-fen" (IllegalArgumentException out of
            // Fen.parse), and "setoption name Hash value abc"
            // (NumberFormatException). An engine that exits mid-match loses it.
            try {
            switch (tok[0]) {
                case "uci" -> {
                    out("id name " + NAME);
                    out("id author " + AUTHOR);
                    out("option name nodestime type spin default 0 min 0 max 10000");
                    out("option name Hash type spin default 64 min 0 max 1024");
                    out("option name Ordering type check default true");
                    out("option name Pvs type check default true");
                    out("option name NullMove type check default true");
                    out("option name Aspiration type check default true");
                    out("option name Quiescence type check default true");
                    out("option name Eval type combo default psqt var psqt var material");
                    out("option name NetFile type string default <empty>");
                    out("option name TuneFile type string default <empty>");
                    out("uciok");
                }
                case "setoption" -> { join(); setOption(tok); }
                // No join(). The spec requires isready to be answered even
                // while searching, and a GUI uses it as a liveness probe. This
                // blocked the reader thread on the search worker, so during
                // "go movetime 8000" readyok arrived eight seconds late, after
                // bestmove, which is the opposite of what it is for.
                case "isready" -> out("readyok");
                // search.tt, not tt: setOption replaces the table when Hash
                // changes, and clearing the original left the live one holding
                // entries from the previous game.
                case "ucinewgame" -> {
                    join();
                    board = Fen.parse(Fen.START);
                    if (search.tt != null) search.tt.clear();
                }
                case "position" -> { join(); position(tok); }
                case "go" -> go(tok);
                case "stop" -> { search.stop(); join(); }
                case "quit" -> { search.stop(); join(); return; }
                default -> { /* UCI says ignore unknown commands */ }
            }
            } catch (RuntimeException e) {
                System.err.println("ignoring \"" + line + "\": " + e);
            }
        }
    }

    private long nodestime;

    private void setOption(String[] tok) {
        // setoption name <key> value <val>
        int valueAt = -1;
        for (int i = 0; i < tok.length; i++) if (tok[i].equals("value")) valueAt = i;
        if (valueAt < 0 || valueAt + 1 >= tok.length) return;
        StringBuilder key = new StringBuilder();
        for (int i = 2; i < valueAt; i++) key.append(i > 2 ? " " : "").append(tok[i]);
        String k = key.toString();
        String v = tok[valueAt + 1];
        if (k.equalsIgnoreCase("nodestime")) {
            nodestime = Long.parseLong(v);
        } else if (k.equalsIgnoreCase("Hash")) {
            // 0 disables the table entirely, which is how the harness measures
            // what the table is worth.
            int mb = Integer.parseInt(v);
            search.tt = (mb <= 0) ? null : new TranspositionTable(mb);
        } else if (k.equalsIgnoreCase("Aspiration")) {
            search.useAspiration = Boolean.parseBoolean(v);
        } else if (k.equalsIgnoreCase("NullMove")) {
            search.useNullMove = Boolean.parseBoolean(v);
        } else if (k.equalsIgnoreCase("Pvs")) {
            search.usePvs = Boolean.parseBoolean(v);
        } else if (k.equalsIgnoreCase("Quiescence")) {
            search.useQuiescence = Boolean.parseBoolean(v);
        } else if (k.equalsIgnoreCase("Ordering")) {
            search.ordering = Boolean.parseBoolean(v) ? new Ordering() : null;
        } else if (k.equalsIgnoreCase("TuneFile")) {
            // Load Texel-tuned values at runtime so the harness can A/B a tuned
            // build against an untuned one without two compiles.
            try {
                strix.eval.Tunable.loadRaw(java.nio.file.Path.of(v));
            } catch (Exception e) {
                System.err.println("could not load " + v + ": " + e.getMessage());
            }
        } else if (k.equalsIgnoreCase("NetFile")) {
            try {
                search.setEvaluator(strix.nnue.NnueEvaluator.load(java.nio.file.Path.of(v)));
            } catch (Exception e) {
                System.err.println("could not load net " + v + ": " + e.getMessage());
            }
        } else if (k.equalsIgnoreCase("Eval")) {
            search.setEvaluator(v.equalsIgnoreCase("material") ? new Material() : new Psqt());
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
            // Every one of these consumes the NEXT token, so a trailing keyword
            // ("go wtime") walked off the end and took the process with it.
            boolean hasValue = i + 1 < tok.length;
            switch (tok[i]) {
                case "wtime" -> { if (hasValue) limits.wtime = Long.parseLong(tok[++i]); }
                case "btime" -> { if (hasValue) limits.btime = Long.parseLong(tok[++i]); }
                case "winc" -> { if (hasValue) limits.winc = Long.parseLong(tok[++i]); }
                case "binc" -> { if (hasValue) limits.binc = Long.parseLong(tok[++i]); }
                case "movetime" -> { if (hasValue) limits.movetime = Long.parseLong(tok[++i]); }
                case "nodes" -> { if (hasValue) limits.nodes = Long.parseLong(tok[++i]); }
                case "depth" -> { if (hasValue) limits.depth = Integer.parseInt(tok[++i]); }
                case "infinite" -> limits.infinite = true;
                default -> { }
            }
        }
        limits.nodestime = nodestime;
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
