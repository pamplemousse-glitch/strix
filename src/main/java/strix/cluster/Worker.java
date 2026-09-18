package strix.cluster;

import strix.harness.Game;
import strix.harness.MatchRunner.EngineSpec;
import strix.harness.Openings;
import strix.harness.UciEngine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Asks for a pair, plays it, reports the result, repeats.
 *
 * The worker is deliberately the dumb half. It holds no state worth preserving,
 * makes no decision about whether its work was needed, and has no opinion on when
 * the run ends. Everything it knows it learned from the last reply.
 *
 * That asymmetry is the point. A worker can be killed at any instant without the
 * coordinator needing to be told, because nothing it was holding was authoritative.
 * The most it can cost is one pair of games being played twice.
 *
 * <h2>The submission it cannot take back</h2>
 * If the process dies between finishing the games and the result POST landing, the
 * work is simply lost and the lease expires. The alternative, persisting results
 * locally and retrying on restart, would trade a lost pair for a much larger
 * surface: worker-side durability, retry backoff, and results arriving arbitrarily
 * late. A pair costs seconds to recompute. It is not worth the machinery.
 */
public final class Worker implements AutoCloseable {

    private final String id;
    private final URI base;
    private final HttpClient http;
    private final EngineSpec engineA, engineB;
    private final String goArgs;
    private final long timeoutMillis;
    private final int maxPlies;

    private UciEngine a, b;
    private int played, dropped;

    public Worker(String id, String baseUrl, EngineSpec engineA, EngineSpec engineB,
                  String goArgs, long timeoutMillis, int maxPlies) {
        this.id = id;
        this.base = URI.create(baseUrl);
        this.engineA = engineA;
        this.engineB = engineB;
        this.goArgs = goArgs;
        this.timeoutMillis = timeoutMillis;
        this.maxPlies = maxPlies;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Runs until the coordinator has nothing left to give. */
    public void run() throws IOException, InterruptedException {
        a = open(engineA);
        b = open(engineB);

        while (true) {
            Reply lease = get("/lease?worker=" + id);
            if (lease.retryLater()) {
                // Queue is empty but jobs are still leased to someone else. Those
                // leases may expire and come back to us, so waiting is the whole
                // reason a dead worker's jobs ever get redone.
                Thread.sleep(RETRY_MILLIS);
                continue;
            }
            if (lease.over()) {
                System.out.printf("%s: run is over, stopping after %d pairs (%d dropped)%n",
                        id, played, dropped);
                return;
            }

            Map<String, String> job = parse(lease.body());
            String key = job.get("key");
            int openingIndex = Integer.parseInt(job.get("opening"));
            String opening = Openings.get(openingIndex);

            // Same opening, both colours, so the opening's own advantage cancels.
            Game.Outcome first = Game.play(a, b, opening, goArgs, timeoutMillis, maxPlies);
            Game.Outcome second = Game.play(b, a, opening, goArgs, timeoutMillis, maxPlies);

            double scoreA = first.result().whiteScore() + (1.0 - second.result().whiteScore());
            int bucket = (int) Math.round(scoreA * 2);      // 0..4 in half-points

            String reply = post("/result", "key " + key + "\nbucket " + bucket
                    + "\ndetail " + first.result() + "/" + second.result() + "\n");

            played++;
            Map<String, String> ack = parse(reply);
            if ("false".equals(ack.get("counted"))) {
                dropped++;
                System.out.printf("%s: %s was already counted, dropped%n", id, key);
            }
            if ("true".equals(ack.get("stop"))) {
                System.out.printf("%s: coordinator says the test has settled%n", id);
                return;
            }
        }
    }

    private static final long RETRY_MILLIS = 2_000;

    /**
     * A lease response, with the two empty cases kept distinct.
     *
     * Naming them is the point. When "no job right now" and "no job ever again"
     * were both a null body, it took one careless equality check to make a worker
     * quit while another worker's jobs were still waiting to be reclaimed.
     */
    private record Reply(int status, String body) {
        boolean retryLater() { return status == 503; }
        boolean over()       { return status == 204; }
    }

    private Reply get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body());
    }

    private String post(String path, String body) throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(base.resolve(path))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static Map<String, String> parse(String body) {
        var out = new java.util.HashMap<String, String>();
        for (String line : body.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            int space = t.indexOf(' ');
            if (space > 0) out.put(t.substring(0, space), t.substring(space + 1));
        }
        return out;
    }

    private static UciEngine open(EngineSpec spec) throws IOException {
        UciEngine e = new UciEngine(spec.name(), spec.command());
        for (var entry : spec.options().entrySet()) e.setOption(entry.getKey(), entry.getValue());
        return e;
    }

    @Override
    public void close() {
        if (a != null) a.close();
        if (b != null) b.close();
    }
}
