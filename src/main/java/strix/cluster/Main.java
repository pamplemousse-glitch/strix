package strix.cluster;

import strix.harness.MatchRunner;
import strix.harness.Sprt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for both halves of the cluster.
 *
 * <pre>
 *   # one coordinator, anywhere
 *   java -cp build/classes/java/main strix.cluster.Main coordinator \
 *        &lt;port&gt; &lt;maxPairs&gt; &lt;leaseSeconds&gt; &lt;log&gt; &lt;elo1&gt;
 *
 *   # any number of workers, on any machine that can reach it
 *   java -cp build/classes/java/main strix.cluster.Main worker \
 *        &lt;url&gt; &lt;id&gt; &lt;nodes&gt; &lt;baselineOpts&gt; &lt;candidateOpts&gt;
 * </pre>
 *
 * Options are {@code Key=Value} pairs separated by commas, or {@code -} for none,
 * matching {@code strix.harness.Main}.
 *
 * <h2>Picking a lease length</h2>
 * The lease is a guess about the slowest worker you are willing to wait for, and
 * both directions cost something. Too short and healthy-but-slow workers keep
 * having their jobs taken, so the cluster does the same work repeatedly. Too long
 * and a dead worker's jobs sit unavailable, which shows up as the run crawling to
 * a halt at the end while the queue is empty and nothing is in flight.
 *
 * A pair at 20,000 nodes per move takes a few seconds on a modern laptop. Sixty
 * seconds is roughly ten times that, which tolerates a machine under heavy load
 * without leaving a dead one's work stranded for long.
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
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "coordinator" -> coordinator(args);
            case "worker"      -> worker(args);
            default            -> usage();
        }
    }

    private static void coordinator(String[] args) throws Exception {
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9000;
        int maxPairs = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        long leaseSecs = args.length > 3 ? Long.parseLong(args[3]) : 60;
        Path log = Path.of(args.length > 4 ? args[4] : "runs/cluster.tsv");
        double elo1 = args.length > 5 ? Double.parseDouble(args[5]) : 5.0;

        if (log.getParent() != null) Files.createDirectories(log.getParent());

        // Bounds must match the expected effect size. See ADR 0012.
        Coordinator c = new Coordinator(new Sprt(0.0, elo1, 0.05, 0.05), log,
                leaseSecs * 1000, maxPairs);
        c.load();

        try (ClusterServer server = new ClusterServer(c, port)) {
            server.start();
            System.out.printf("coordinator on :%d  cap %d pairs  lease %ds  log %s%n",
                    server.port(), maxPairs, leaseSecs, log);
            System.out.printf("H0: equal.  H1: baseline is at least %.0f Elo better.%n%n", elo1);
            System.out.println("  java -cp build/classes/java/main strix.cluster.Main worker "
                    + "http://<this-host>:" + server.port() + " w1 20000 - -");
            System.out.println();

            Coordinator.Status last = null;
            while (!c.stopped()) {
                Coordinator.Status s = c.status();
                if (!s.equals(last)) {
                    System.out.printf("  counted %d  pending %d  inflight %d  dup %d  expired %d  %+.1f Elo  llr %+.2f%n",
                            s.counted(), s.pending(), s.inFlight(), s.duplicates(),
                            s.expired(), s.elo(), s.llr());
                    last = s;
                }
                Thread.sleep(1000);
            }

            // One snapshot for every line below, so the verdict and the count
            // that supposedly produced it cannot disagree.
            Coordinator.Status s = c.status();
            System.out.printf("%nverdict: %s after %d games%n", s.verdict(), s.games());
            System.out.printf("baseline is %+.1f Elo vs candidate%n", s.elo());
            System.out.printf("%d duplicate results dropped, %d leases expired, %d late%n",
                    s.duplicates(), s.expired(), s.late());
        }
    }

    private static void worker(String[] args) throws Exception {
        String url = args.length > 1 ? args[1] : "http://localhost:9000";
        String id = args.length > 2 ? args[2] : "w1";
        long nodes = args.length > 3 ? Long.parseLong(args[3]) : 20_000;
        String baseOpts = args.length > 4 ? args[4] : "-";
        String candOpts = args.length > 5 ? args[5] : "-";

        System.out.printf("worker %s -> %s  (%d nodes/move)%n", id, url, nodes);
        try (Worker w = new Worker(id, url, spec("baseline", baseOpts), spec("candidate", candOpts),
                "nodes " + nodes, 60_000, 300)) {
            w.run();
        }
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

    private static void usage() {
        System.err.println("""
                usage:
                  strix.cluster.Main coordinator <port> <maxPairs> <leaseSeconds> <log> <elo1>
                  strix.cluster.Main worker <url> <id> <nodes> <baselineOpts> <candidateOpts>
                """);
    }
}
