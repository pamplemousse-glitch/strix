package strix.tune;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Relabels positions with Stockfish evaluations instead of game outcomes.
 *
 * <h2>Why, specifically</h2>
 * Texel tuning failed at -57.6 Elo (ADR 0013) because the label was wrong. "The
 * game this position came from was eventually won" is an extremely noisy signal
 * when the games were played by a weak engine at 4,000 nodes: the result usually
 * turned on a later blunder, not on anything true about this position.
 *
 * A Stockfish evaluation of the position itself has no such problem. It is an
 * opinion about THIS position from something far stronger than the thing being
 * trained.
 *
 * <h2>Depth, and why it is low</h2>
 * Depth 8 rather than 20. The signal wanted is "roughly how good is this", and
 * the difference between depth 8 and depth 20 is mostly tactics that the training
 * set does not need and cannot represent. Low depth buys an order of magnitude
 * more positions, which matters more.
 */
public final class Label {

    private static final int DEPTH = 8;

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args.length > 0 ? args[0] : "runs/positions.txt");
        Path out = Path.of(args.length > 1 ? args[1] : "runs/labelled.txt");
        int workers = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        int limit = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;

        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        int total = Math.min(lines.size(), limit);
        System.out.printf("labelling %,d positions with stockfish depth %d, %d workers%n",
                total, DEPTH, workers);

        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        Object lock = new Object();

        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            Thread[] threads = new Thread[workers];
            for (int t = 0; t < workers; t++) {
                threads[t] = new Thread(() -> {
                    try (Stockfish sf = new Stockfish()) {
                        StringBuilder buf = new StringBuilder();
                        int buffered = 0;
                        while (true) {
                            int i = next.getAndIncrement();
                            if (i >= total) break;
                            Dataset.Row row = Dataset.parse(lines.get(i));
                            if (row == null) continue;
                            String fen = row.fen();

                            Integer cp = sf.evaluate(fen, DEPTH);
                            if (cp == null) continue;          // mate score, skip it

                            // The game id has to survive relabelling. These
                            // workers write into one file in whatever order they
                            // finish, so by the time the trainer reads it a
                            // game's positions are no longer even adjacent, and
                            // the id is the only thing left that groups them.
                            buf.append(Dataset.format(fen, String.valueOf(cp), row.gameId()))
                               .append('\n');
                            if (++buffered >= 200) {
                                synchronized (lock) { w.write(buf.toString()); }
                                buf.setLength(0);
                                buffered = 0;
                            }
                            int d = done.incrementAndGet();
                            if (d % 10_000 == 0) {
                                System.out.printf("  %,d / %,d%n", d, total);
                            }
                        }
                        if (buffered > 0) synchronized (lock) { w.write(buf.toString()); }
                    } catch (Exception e) {
                        System.err.println("worker died: " + e.getMessage());
                    }
                });
                threads[t].start();
            }
            for (Thread th : threads) th.join();
        }
        System.out.printf("done: %,d labelled -> %s%n", done.get(), out);
    }

    /** A Stockfish process held open, since spawning one per position would dominate. */
    private static final class Stockfish implements AutoCloseable {
        private final Process proc;
        private final BufferedReader out;
        private final BufferedWriter in;

        Stockfish() throws IOException {
            proc = new ProcessBuilder("stockfish").start();
            out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
            send("uci");
            await("uciok");
            send("setoption name Threads value 1");
            send("setoption name Hash value 16");
            send("isready");
            await("readyok");
        }

        private void send(String s) throws IOException {
            in.write(s); in.write('\n'); in.flush();
        }

        private String await(String prefix) throws IOException {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith(prefix)) return line;
            }
            throw new IOException("stockfish closed");
        }

        /** Centipawns from the side to move's view, or null for a mate score. */
        Integer evaluate(String fen, int depth) throws IOException {
            send("position fen " + fen);
            send("go depth " + depth);
            Integer cp = null;
            boolean mate = false;
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith("bestmove")) break;
                if (!line.startsWith("info")) continue;
                int c = line.indexOf(" score cp ");
                if (c >= 0) {
                    mate = false;
                    cp = parseFirstInt(line.substring(c + 10));
                } else if (line.contains(" score mate ")) {
                    mate = true;
                }
            }
            // Mate scores are not a centipawn opinion, they are a different kind of
            // claim, and training on a clamped 30000 teaches the network nonsense.
            return mate ? null : cp;
        }

        private static Integer parseFirstInt(String s) {
            s = s.trim();
            int sp = s.indexOf(' ');
            try { return Integer.parseInt(sp < 0 ? s : s.substring(0, sp)); }
            catch (NumberFormatException e) { return null; }
        }

        @Override public void close() {
            try { send("quit"); } catch (IOException ignored) { }
            proc.destroy();
        }
    }
}
