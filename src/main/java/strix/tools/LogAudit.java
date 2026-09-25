package strix.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reports how much of a run log is real evidence and how much is replay.
 *
 * <h2>Why this exists</h2>
 * The harness drew openings as {@code pairIndex % 48} against a 48-line book,
 * and the search is deterministic at a fixed node count, so pair 0 and pair 48
 * were the same game move for move. {@code runs/texel-sprt.tsv} holds 280 pairs
 * and 48 distinct results; {@code runs/hash-tight.tsv} holds 385 and 48.
 *
 * The LLR is linear in the bucket counts, so replaying a sample k times
 * multiplies it by k while adding no information, and past the book size the
 * test crosses a bound with probability approaching 1 in whichever direction
 * those games happen to lean. See ADR 0016.
 *
 * The defect is fixed in {@code Openings.lineFor}. This tool is the gate that
 * keeps it fixed: a ratio above 1.0 means the run is not what it claims.
 */
public final class LogAudit {

    private LogAudit() {}

    public record Audit(int recorded, int distinct, int distinctOpenings) {
        /** Recorded pairs per genuinely distinct result. 1.0 is clean. */
        public double replication() {
            return distinct == 0 ? 0 : recorded / (double) distinct;
        }
        public boolean clean() { return distinct > 0 && recorded == distinct; }
    }

    /**
     * A log line is {@code <openingIndex>:<pairIndex>\t<bucket>\t<detail>} in the
     * legacy format, or {@code <pairIndex>\t<bucket>\t<detail>} now.
     *
     * Identity for "is this the same game" is deliberately (opening, bucket,
     * detail) and NOT the key: two different pair indices that produced a
     * byte-identical game are the replication being measured, and keying on the
     * pair index would hide exactly that.
     */
    public static Audit audit(List<String> lines) {
        Set<String> distinct = new HashSet<>();
        Set<String> openings = new HashSet<>();
        int recorded = 0;

        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 3) continue;
            recorded++;

            String key = parts[0].trim();
            int colon = key.indexOf(':');
            String opening = colon < 0 ? "?" : key.substring(0, colon);
            openings.add(opening);

            distinct.add(opening + "|" + parts[1].trim() + "|" + parts[2].trim());
        }
        return new Audit(recorded, distinct.size(), openings.size());
    }

    public static Audit audit(Path log) throws IOException {
        return audit(Files.readAllLines(log, StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: LogAudit <run.tsv> [more.tsv ...]");
            System.exit(2);
        }
        System.out.printf("%-28s %10s %10s %12s  %s%n",
                "log", "recorded", "distinct", "replication", "verdict");

        boolean allClean = true;
        for (String arg : args) {
            Path p = Path.of(arg);
            if (!Files.exists(p)) {
                System.out.printf("%-28s %10s%n", p.getFileName(), "MISSING");
                allClean = false;
                continue;
            }
            Audit a = audit(p);
            boolean clean = a.clean();
            allClean &= clean;
            System.out.printf("%-28s %10d %10d %11.2fx  %s%n",
                    p.getFileName(), a.recorded(), a.distinct(), a.replication(),
                    clean ? "clean" : "REPLAYED, not evidence");
        }

        if (!allClean) {
            System.out.printf("%nA replication above 1.00x means the same game was counted more%n");
            System.out.printf("than once. The LLR is linear in the counts, so the verdict is%n");
            System.out.printf("inflated by that factor. See ADR 0016.%n");
            System.exit(1);
        }
    }
}
