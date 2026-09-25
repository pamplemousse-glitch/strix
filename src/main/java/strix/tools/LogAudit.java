package strix.tools;

import strix.harness.Openings;

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
 * and 48 distinct results. The LLR is linear in the bucket counts, so replaying
 * a sample k times multiplies it by k while adding no information. See ADR 0016.
 *
 * <h2>What identity to use, and the wrong answer I tried first</h2>
 * The first version fingerprinted a pair by {@code (opening, bucket, detail)}.
 * That is a property of the RESULT, and it is wrong in both directions: two
 * genuinely different games that happen to end the same way collapse into one
 * (a false alarm), and the current log format does not even carry the opening
 * index any more, so every row collapsed together and a clean 97-pair run was
 * reported as 6.06x replayed.
 *
 * The identity that actually decides it is the **starting position**. The search
 * is deterministic at fixed nodes, so two pairs are the same game if and only if
 * they began from the same line. {@link Openings#lineFor} is a pure function of
 * the pair index, so the log's pair indices are enough to compute this exactly,
 * with no false alarms and nothing to store.
 */
public final class LogAudit {

    private LogAudit() {}

    /**
     * How much duplication disqualifies a run.
     *
     * Not zero, and the reason is arithmetic rather than tolerance.
     * {@link Openings#lineFor} extends a book line with plies seeded from the
     * pair index, and two different seeds can pick the same moves. Measured: one
     * duplicate in 373 pairs, about 0.27%.
     *
     * The LLR is linear in the counts, so k duplicates in n pairs inflate it by
     * n/(n-k). At 1% that is a factor of 1.01, which cannot move a verdict. The
     * defect this gate exists to catch was a factor of 8.
     *
     * A threshold of exactly 1.00x would fail honest runs for rounding, which is
     * the fastest way to teach someone to ignore a gate.
     */
    private static final double MAX_REPLICATION = 1.01;

    public record Audit(int recorded, int distinctLines, int malformed, boolean legacy) {
        /** Recorded pairs per genuinely distinct starting line. 1.0 is perfect. */
        public double replication() {
            return distinctLines == 0 ? 0 : recorded / (double) distinctLines;
        }
        public int duplicates() { return recorded - distinctLines; }
        public boolean clean() {
            return distinctLines > 0 && replication() <= MAX_REPLICATION;
        }
    }

    /**
     * Pair index from a log key, tolerating both formats.
     *
     * Legacy keys are {@code <openingIndex>:<pairIndex>}; current keys are the
     * pair index alone, because the opening index is derived from it and had no
     * business in the identity (see Job.key).
     */
    static int pairIndexOf(String key) {
        String k = key.trim();
        int colon = k.indexOf(':');
        return Integer.parseInt(colon < 0 ? k : k.substring(colon + 1));
    }

    /**
     * Which opening function produced this run.
     *
     * The tool can only compute what a line WOULD be today, so on a log written
     * before {@link Openings#lineFor} existed it would report the run as clean
     * when those games really were identical. The key format is the available
     * discriminator: {@code opening:pair} keys predate the fix, bare pair
     * indices postdate it.
     *
     * This is a heuristic, and it is stated rather than hidden. A log written in
     * the window between the two commits would be misread, and none exists.
     */
    static boolean looksLegacy(List<String> lines) {
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length >= 3) return parts[0].indexOf(':') >= 0;
        }
        return false;
    }

    public static Audit audit(List<String> lines) {
        boolean legacy = looksLegacy(lines);
        Set<String> startingLines = new HashSet<>();
        int recorded = 0, malformed = 0;

        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 3) { if (!line.isBlank()) malformed++; continue; }
            try {
                int pair = pairIndexOf(parts[0]);
                // Legacy runs cycled the book with no diversification, so their
                // line is Openings.get, not lineFor.
                startingLines.add(legacy ? Openings.get(pair) : Openings.lineFor(pair));
                recorded++;
            } catch (RuntimeException e) {
                malformed++;
            }
        }
        return new Audit(recorded, startingLines.size(), malformed, legacy);
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
                "log", "pairs", "distinct", "replication", "verdict");

        boolean allClean = true;
        for (String arg : args) {
            Path p = Path.of(arg);
            if (!Files.exists(p)) {
                System.out.printf("%-28s %10s%n", p.getFileName(), "MISSING");
                allClean = false;
                continue;
            }
            Audit a = audit(p);
            allClean &= a.clean();
            String note = a.clean()
                    ? (a.duplicates() == 0 ? "clean"
                            : "clean (" + a.duplicates() + " duplicate line, immaterial)")
                    : "REPLAYED, not evidence";
            System.out.printf("%-28s %10d %10d %11.3fx  %s%s%n",
                    p.getFileName(), a.recorded(), a.distinctLines(), a.replication(),
                    note, a.legacy() ? "  (pre-fix run)" : "");
        }

        if (!allClean) {
            System.out.printf("%nA replication above 1.00x means two pairs started from the same%n");
            System.out.printf("line, and a deterministic search makes those the same game. The LLR%n");
            System.out.printf("is linear in the counts, so the verdict is inflated by that factor.%n");
            System.out.printf("See ADR 0016.%n");
            System.exit(1);
        }
    }
}
