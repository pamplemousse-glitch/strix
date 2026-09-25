package strix.harness;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an SPRT match between two configurations of Strix.
 *
 * <pre>
 *   java -cp ... strix.harness.Main \
 *        &lt;workers&gt; &lt;nodes&gt; &lt;maxPairs&gt; &lt;log&gt; &lt;baselineOpts&gt; &lt;candidateOpts&gt; &lt;elo1&gt;
 * </pre>
 *
 * Options are {@code Key=Value} pairs separated by commas, or {@code -} for none.
 * Both sides are configurable, because the interesting question is not always
 * "what does removing X cost" but sometimes "what does adding X buy".
 *
 * Elo is reported as baseline minus candidate, so H1_ACCEPTED means the baseline
 * won. Put the thing you are hoping is better in the baseline slot.
 */
public final class Main {

    private static final List<String> STRIX =
            List.of("java", "-cp", engineClasspath(), "strix.uci.Main");

    /**
     * Classpath the engine subprocesses are launched from.
     *
     * Configurable because the default is a live build directory, and a run
     * that recompiles underneath itself measures two different engines and
     * reports one number. Observed directly: a transposition-table measurement
     * and a PVS measurement were both running when null move pruning was
     * compiled in, and both were silently invalidated mid-flight. Nothing
     * failed, nothing warned, and the verdicts looked ordinary.
     *
     * Point STRIX_ENGINE_CP at a frozen jar for any measurement that matters.
     * See ops/measure.
     */
    private static String engineClasspath() {
        String cp = System.getenv("STRIX_ENGINE_CP");
        return (cp == null || cp.isBlank()) ? "build/classes/java/main" : cp;
    }

    public static void main(String[] args) throws Exception {
        int workers = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        long nodes = args.length > 1 ? Long.parseLong(args[1]) : 20_000;
        int maxPairs = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        Path log = Path.of(args.length > 3 ? args[3] : "runs/match.tsv");
        String baseOpts = args.length > 4 ? args[4] : "-";
        String candOpts = args.length > 5 ? args[5] : "-";
        double elo1 = args.length > 6 ? Double.parseDouble(args[6]) : 5.0;

        // getParent() is null for a bare filename like "match.tsv", which NPE'd
        // before a single game was played.
        Path parent = log.getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);

        var baseline = spec("baseline", baseOpts);
        var candidate = spec("candidate", candOpts);

        var config = new MatchRunner.Config(
                baseline, candidate,
                "nodes " + nodes,       // fixed nodes: reproducible, ignores machine load
                60_000, 300, workers, maxPairs, log);

        // Bounds must match the expected effect size. See ADR 0012.
        Sprt sprt = new Sprt(0.0, elo1, 0.05, 0.05);
        System.out.printf("baseline [%s]  vs  candidate [%s]%n", baseOpts, candOpts);
        System.out.printf("%d nodes/move, %d workers, cap %d pairs%n", nodes, workers, maxPairs);
        System.out.printf("H0: equal.  H1: baseline is at least %.0f Elo better.%n%n", elo1);

        long t0 = System.currentTimeMillis();
        new MatchRunner(config, sprt).run();
        long secs = (System.currentTimeMillis() - t0) / 1000;

        System.out.printf("%n%s%n", sprt);
        System.out.printf("verdict: %s after %d games in %ds%n", sprt.verdict(), sprt.gameCount(), secs);
        System.out.printf("baseline is %+.1f Elo vs candidate%n", sprt.elo());
    }

    private static MatchRunner.EngineSpec spec(String name, String opts) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!opts.equals("-") && !opts.isBlank()) {
            for (String pair : opts.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return new MatchRunner.EngineSpec(name, STRIX, map);
    }
}
