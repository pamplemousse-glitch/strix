package strix.tune;

import strix.core.*;
import strix.eval.Psqt;
import strix.harness.Openings;
import strix.search.*;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates the labelled dataset Texel tuning needs: positions paired with how
 * the game they came from actually ended.
 *
 * Games are played in-process rather than over UCI, because this needs hundreds
 * of thousands of positions and subprocess overhead would dominate.
 *
 * <h2>Which positions are kept</h2>
 * Only QUIET ones. A position in the middle of a capture sequence has a static
 * evaluation that means nothing, so fitting evaluation parameters to it teaches
 * the tuner noise. Three filters:
 *
 * <ul>
 *   <li>Not in check.</li>
 *   <li>The search's best move is not a capture or promotion.</li>
 *   <li>At least 8 plies in, so opening-book positions do not dominate.</li>
 * </ul>
 *
 * <h2>Why the randomness</h2>
 * Two deterministic engines from the same opening play the same game every time.
 * A few random plies at the start, plus occasional random choices among the top
 * moves, spreads the dataset across positions the engine would not otherwise
 * reach. Without it you get thousands of copies of one game.
 */
public final class SelfPlay {

    private static final int NODES = 4_000;      // shallow on purpose: quantity over quality
    private static final int MAX_PLIES = 240;
    private static final int RANDOM_OPENING_PLIES = 2;

    public static void main(String[] args) throws Exception {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 2_000;
        Path out = Path.of(args.length > 1 ? args[1] : "runs/positions.txt");
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        Files.createDirectories(out.getParent());

        AtomicInteger played = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        Object lock = new Object();

        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                workers[t] = new Thread(() -> {
                    SplittableRandom rng = new SplittableRandom(0x5721A9C3L + seed);
                    Search search = new Search(new Psqt());
                    search.tt = new TranspositionTable(16);
                    search.ordering = new Ordering();
                    StringBuilder buf = new StringBuilder();

                    while (true) {
                        int n = played.incrementAndGet();
                        if (n > games) return;
                        int lines = playOne(search, rng, buf);
                        synchronized (lock) {
                            try { w.write(buf.toString()); } catch (Exception ignored) { }
                        }
                        buf.setLength(0);
                        int total = written.addAndGet(lines);
                        if (n % 100 == 0) {
                            System.out.printf("  %d games, %,d positions%n", n, total);
                        }
                    }
                }, "selfplay-" + t);
                workers[t].start();
            }
            for (Thread th : workers) th.join();
        }
        System.out.printf("done: %d games, %,d positions -> %s%n", games, written.get(), out);
    }

    private static int playOne(Search search, SplittableRandom rng, StringBuilder out) {
        Board board = Fen.parse(Fen.START);
        String opening = Openings.get(rng.nextInt(Openings.size()));
        for (String u : opening.trim().split("\\s+")) {
            int m = find(board, u);
            if (m == Move.NONE) break;
            board.make(m);
        }
        // A couple of random plies so identical openings still diverge.
        for (int i = 0; i < RANDOM_OPENING_PLIES; i++) {
            int[] moves = new int[MoveGen.MAX_MOVES];
            int n = MoveGen.generateLegal(board, moves, new int[MoveGen.MAX_MOVES]);
            if (n == 0) return 0;
            board.make(moves[rng.nextInt(n)]);
        }

        SearchLimits limits = new SearchLimits();
        limits.nodes = NODES;
        limits.depth = 64;

        var fens = new java.util.ArrayList<String>();
        GameResult result = GameResult.ONGOING;
        int ply = 0;

        while (ply < MAX_PLIES) {
            result = GameResult.of(board);
            if (result.isOver()) break;

            search.think(board, limits);
            int best = search.bestMove;
            if (best == Move.NONE) break;

            boolean quiet = !board.inCheck(board.sideToMove)
                    && !Move.isCapture(best) && !Move.isPromotion(best);
            if (quiet && ply >= 8) fens.add(Fen.emit(board));

            board.make(best);
            ply++;
        }
        if (!result.isOver()) result = GameResult.DRAW_FIFTY_MOVE;   // hit the cap

        String label = switch (result) {
            case WHITE_WINS -> "1.0";
            case BLACK_WINS -> "0.0";
            default -> "0.5";
        };
        for (String fen : fens) out.append(fen).append(" | ").append(label).append('\n');
        return fens.size();
    }

    private static int find(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals(uci)) return m[i];
        return Move.NONE;
    }
}
