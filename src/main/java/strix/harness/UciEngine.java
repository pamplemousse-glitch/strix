package strix.harness;

import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives one UCI engine as a subprocess.
 *
 * The same code speaks to Strix and to Stockfish, because UCI is just lines of
 * text. The harness does not know or care which is which, which is what lets a
 * patched build play against its own baseline.
 */
public final class UciEngine implements AutoCloseable {

    private final Process process;
    private final BufferedReader out;
    private final BufferedWriter in;
    public final String name;

    public UciEngine(String name, List<String> command) throws IOException {
        this.name = name;
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        process = pb.start();
        out = new BufferedReader(new InputStreamReader(process.getInputStream()));
        in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        send("uci");
        awaitPrefix("uciok", 10_000);
    }

    public void send(String line) throws IOException {
        in.write(line);
        in.write('\n');
        in.flush();
    }

    public void setOption(String key, String value) throws IOException {
        send("setoption name " + key + " value " + value);
    }

    public void newGame() throws IOException {
        send("ucinewgame");
        send("isready");
        awaitPrefix("readyok", 10_000);
    }

    /** A move plus the score the engine reported for it, in centipawns. */
    public record Reply(String move, int scoreCp, boolean hasScore) {}

    /**
     * Ask for a move. {@code goArgs} is whatever follows "go", so the caller picks
     * between a clock, fixed nodes, or anything else.
     *
     * The last reported score is captured on the way past. Adjudication needs both
     * engines' opinions and the info lines are the only place they appear.
     */
    public Reply bestMove(String movesSoFar, String goArgs, long timeoutMillis) throws IOException {
        send(movesSoFar.isEmpty() ? "position startpos" : "position startpos moves " + movesSoFar);
        send("go " + goArgs);

        int score = 0;
        boolean hasScore = false;
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (System.currentTimeMillis() < deadline) {
            if (!out.ready()) {
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                continue;
            }
            String line = out.readLine();
            if (line == null) throw new IOException(name + " died");

            if (line.startsWith("bestmove")) {
                String[] parts = line.split("\\s+");
                return new Reply(parts.length > 1 ? parts[1] : "0000", score, hasScore);
            }
            if (!line.startsWith("info")) continue;

            int cp = line.indexOf(" score cp ");
            if (cp >= 0) {
                Integer v = firstInt(line.substring(cp + 10));
                if (v != null) { score = v; hasScore = true; }
                continue;
            }
            int mate = line.indexOf(" score mate ");
            if (mate >= 0) {
                Integer v = firstInt(line.substring(mate + 12));
                if (v != null) { score = v > 0 ? 30_000 : -30_000; hasScore = true; }
            }
        }
        throw new IOException(name + " timed out waiting for bestmove");
    }

    private static Integer firstInt(String rest) {
        rest = rest.trim();
        int sp = rest.indexOf(' ');
        try {
            return Integer.parseInt(sp < 0 ? rest : rest.substring(0, sp));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String awaitPrefix(String prefix, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!out.ready()) {
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                continue;
            }
            String line = out.readLine();
            if (line == null) throw new IOException(name + " died");
            if (line.startsWith(prefix)) return line;
        }
        throw new IOException(name + " timed out waiting for '" + prefix + "'");
    }

    @Override
    public void close() {
        try { send("quit"); } catch (IOException ignored) { }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
