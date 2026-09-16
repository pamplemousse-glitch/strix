package strix.harness;

import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives one UCI engine as a subprocess.
 *
 * Note that this speaks the same protocol to Strix and to Stockfish, because UCI
 * is just lines of text over stdin and stdout. The harness does not know or care
 * which is which, which is what lets a patched build play its own baseline.
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
        await("uciok", 10_000);
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
        await("readyok", 10_000);
    }

    /** Returns the move in UCI notation, or "0000" if the engine has none. */
    public String bestMove(String movesSoFar, long movetimeMillis) throws IOException {
        send(movesSoFar.isEmpty() ? "position startpos" : "position startpos moves " + movesSoFar);
        send("go movetime " + movetimeMillis);
        String line = await("bestmove", movetimeMillis * 4 + 5_000);
        String[] parts = line.split("\\s+");
        return parts.length > 1 ? parts[1] : "0000";
    }

    private String await(String prefix, long timeoutMillis) throws IOException {
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
