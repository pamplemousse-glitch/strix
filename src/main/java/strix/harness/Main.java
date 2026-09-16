package strix.harness;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs an SPRT match between two configurations of Strix.
 *
 *   java -cp ... strix.harness.Main [workers] [nodes] [maxPairs] [logfile]
 *
 * The baseline is full-strength Strix. The candidate is whatever the second spec
 * says, which is how the harness measures what any single feature is worth.
 */
public final class Main {

    private static final List<String> STRIX =
            List.of("java", "-cp", "build/classes/java/main", "strix.uci.Main");

    public static void main(String[] args) throws Exception {
        int workers = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        long nodes = args.length > 1 ? Long.parseLong(args[1]) : 50_000;
        int maxPairs = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        Path log = Path.of(args.length > 3 ? args[3] : "runs/match.tsv");
        String cripple = args.length > 4 ? args[4] : "Ordering=false";
        double elo1 = args.length > 5 ? Double.parseDouble(args[5]) : 5.0;

        java.nio.file.Files.createDirectories(log.getParent());

        String[] kv = cripple.split("=", 2);
        var baseline = MatchRunner.EngineSpec.of("strix-base", STRIX);
        var candidate = new MatchRunner.EngineSpec("strix-" + kv[0].toLowerCase(), STRIX,
                Map.of(kv[0], kv[1]));

        var config = new MatchRunner.Config(
                baseline, candidate,
                "nodes " + nodes,          // fixed nodes: reproducible, machine-load independent
                60_000, 300, workers, maxPairs, log);

        // Bounds must match the effect size you expect. [0, 5] is Fishtest's, for
        // patches worth a few Elo. Using it to measure a whole feature ablation,
        // worth hundreds of Elo, makes both hypotheses fit equally badly and the
        // likelihood ratio between two bad fits grows very slowly.
        Sprt sprt = new Sprt(0.0, elo1, 0.05, 0.05);
        System.out.printf("%s vs %s   %d nodes/move, %d workers, cap %d pairs%n",
                baseline.name(), candidate.name(), nodes, workers, maxPairs);
        System.out.printf("H0: equal.  H1: baseline is at least %.0f Elo better.%n%n", elo1);

        long t0 = System.currentTimeMillis();
        new MatchRunner(config, sprt).run();
        long secs = (System.currentTimeMillis() - t0) / 1000;

        System.out.printf("%n%s%n", sprt);
        System.out.printf("verdict: %s after %d games in %ds%n", sprt.verdict(), sprt.gameCount(), secs);
        System.out.printf("baseline is %+.1f Elo vs the crippled build%n", sprt.elo());
    }
}
