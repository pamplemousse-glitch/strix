package strix.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays a FIXED number of games against an external engine and reports a
 * strength estimate with an error bar.
 *
 * <h2>Why this is not SPRT</h2>
 * SPRT answers "is A better than B" and stops as soon as it knows. That is the
 * right test for "did this patch help" and the wrong one for "how strong is this
 * engine", which needs a known reference and a sample that does not stop early
 * on a lucky streak.
 *
 * <h2>Why it matters here</h2>
 * Every Elo figure this project reports is self-play, and self-play inflates:
 * two builds of one engine share every blind spot, and the published rule of
 * thumb is that roughly 60% of a self-play gain survives contact with a
 * different opponent. The Lichess rating is external but depends on whoever
 * happened to accept a challenge. Neither is a controlled measurement against a
 * known strength.
 *
 * Stockfish's {@code UCI_LimitStrength} plus {@code UCI_Elo} is a calibrated
 * opponent available locally, so running this at several rungs and finding where
 * the score crosses 50% gives an estimate that depends on neither self-play nor
 * Lichess.
 */
public final class Gauntlet {

    private Gauntlet() {}

    public record Result(int wins, int draws, int losses) {
        public int games() { return wins + draws + losses; }
        public double score() { return games() == 0 ? 0 : (wins + 0.5 * draws) / games(); }

        /** Elo difference implied by the score. Saturates rather than diverging. */
        public double elo() {
            double s = score();
            if (s <= 0.0) return -800;
            if (s >= 1.0) return 800;
            return -400.0 * Math.log10(1.0 / s - 1.0);
        }

        /**
         * Half-width of the 95% interval on the Elo difference.
         *
         * Propagated from the standard error of the mean score through the
         * derivative of the Elo curve, which is the usual approximation and is
         * good well away from 0 and 1. Draws count as half a point, so the
         * variance uses the per-game score rather than a win rate.
         */
        public double margin() {
            int n = games();
            if (n < 2) return 800;
            double s = score();
            double variance = (wins * Math.pow(1 - s, 2)
                    + draws * Math.pow(0.5 - s, 2)
                    + losses * Math.pow(0 - s, 2)) / (n - 1);
            double se = Math.sqrt(variance / n);
            double clamped = Math.min(Math.max(s, 1e-4), 1 - 1e-4);
            double derivative = 400.0 / (Math.log(10) * clamped * (1 - clamped));
            return 1.96 * se * derivative;
        }

        @Override public String toString() {
            return String.format("%d-%d-%d (W-D-L) score %.4f  Elo %+.1f +/- %.1f",
                    wins, draws, losses, score(), elo(), margin());
        }
    }

    /**
     * @param ours        command for the engine under test
     * @param theirs      command for the reference engine
     * @param theirOpts   UCI options for the reference, e.g. UCI_Elo
     * @param pairs       game PAIRS; each is one opening played from both sides
     */
    public static Result play(List<String> ours, List<String> theirs,
                              java.util.Map<String, String> theirOpts,
                              String goArgs, long timeoutMillis, int maxPlies,
                              int pairs, Path log) throws IOException, InterruptedException {
        int wins = 0, draws = 0, losses = 0;

        try (UciEngine a = new UciEngine("strix", ours);
             UciEngine b = new UciEngine("reference", theirs);
             var writer = log == null ? null : Files.newBufferedWriter(log,
                     StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            for (var e : theirOpts.entrySet()) b.setOption(e.getKey(), e.getValue());

            for (int p = 0; p < pairs; p++) {
                // Same line from both sides, so the opening's own advantage
                // cancels instead of being attributed to an engine.
                String opening = Openings.lineFor(p);

                Game.Outcome first = Game.play(a, b, opening, goArgs, timeoutMillis, maxPlies);
                Game.Outcome second = Game.play(b, a, opening, goArgs, timeoutMillis, maxPlies);

                double oursScore = first.result().whiteScore() + (1.0 - second.result().whiteScore());
                // Two games, so the pair score is 0, 0.5, 1, 1.5 or 2.
                if (oursScore >= 1.75) { wins += 2; }
                else if (oursScore >= 1.25) { wins++; draws++; }
                else if (oursScore >= 0.75) { draws += 2; }
                else if (oursScore >= 0.25) { losses++; draws++; }
                else { losses += 2; }

                if (writer != null) {
                    writer.write(p + "\t" + (int) Math.round(oursScore * 2) + "\t"
                            + first.result() + "/" + second.result());
                    writer.newLine();
                    writer.flush();
                }
                if ((p + 1) % 10 == 0) {
                    System.out.println("  " + new Result(wins, draws, losses));
                }
            }
        }
        return new Result(wins, draws, losses);
    }

    private static String engineClasspath() {
        String cp = System.getenv("STRIX_ENGINE_CP");
        return (cp == null || cp.isBlank()) ? "build/classes/java/main" : cp;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Gauntlet <referenceElo> [pairs] [nodes] [referenceCmd...]");
            System.err.println("  e.g. Gauntlet 1600 50 20000");
            System.exit(2);
        }
        int refElo = Integer.parseInt(args[0]);
        int pairs = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        long nodes = args.length > 2 ? Long.parseLong(args[2]) : 20_000;

        List<String> ours = List.of("java", "-cp", engineClasspath(), "strix.uci.Main");
        List<String> theirs = args.length > 3
                ? new ArrayList<>(List.of(args).subList(3, args.length))
                : List.of("stockfish");

        var opts = new java.util.LinkedHashMap<String, String>();
        opts.put("UCI_LimitStrength", "true");
        opts.put("UCI_Elo", String.valueOf(refElo));

        Path log = Path.of("runs/gauntlet-" + refElo + ".tsv");
        Files.createDirectories(log.getParent());
        Files.deleteIfExists(log);

        System.out.printf("Strix vs %s at UCI_Elo %d, %d pairs, %d nodes/move%n",
                theirs.get(0), refElo, pairs, nodes);

        Result r = play(ours, theirs, opts, "nodes " + nodes, 60_000, 300, pairs, log);

        System.out.println();
        System.out.println("final: " + r);
        System.out.printf("implied strength: %.0f Elo (reference %d %+.1f)%n",
                refElo + r.elo(), refElo, r.elo());
    }
}
