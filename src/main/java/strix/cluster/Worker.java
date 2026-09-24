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

    private final long giveUpMillis;

    private UciEngine a, b;
    private int played, dropped;

    public Worker(String id, String baseUrl, EngineSpec engineA, EngineSpec engineB,
                  String goArgs, long timeoutMillis, int maxPlies) {
        this(id, baseUrl, engineA, engineB, goArgs, timeoutMillis, maxPlies, DEFAULT_GIVE_UP_MILLIS);
    }

    /** {@code giveUpMillis} is a parameter so the retry ceiling is testable in
     *  seconds rather than in the quarter of an hour a real worker waits. */
    Worker(String id, String baseUrl, EngineSpec engineA, EngineSpec engineB,
           String goArgs, long timeoutMillis, int maxPlies, long giveUpMillis) {
        this.giveUpMillis = giveUpMillis;
        this.id = id;
        this.base = URI.create(baseUrl);
        this.engineA = engineA;
        this.engineB = engineB;
        this.goArgs = goArgs;
        this.timeoutMillis = timeoutMillis;
        this.maxPlies = maxPlies;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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
            if (lease.unexpected()) {
                // Used to fall straight through to parsing the body as a job,
                // where Integer.parseInt(null) killed the worker. A 502 from a
                // reverse proxy or a 302 from a captive portal is exactly the
                // transient condition the retry loop exists to survive.
                System.out.printf("%s: lease returned %d, waiting%n", id, lease.status());
                Thread.sleep(RETRY_MILLIS);
                continue;
            }

            Map<String, String> job = parse(lease.body());
            String key = job.get("key");
            // By pair index, not book index: past the end of the book the line is
            // extended with plies seeded from the pair, so pair 0 and pair 48 are
            // no longer the identical game. Still deterministic per pair, which is
            // what the coordinator's duplicate dropping relies on. See Openings.
            int pairIndex = Integer.parseInt(job.get("pair"));
            String opening = Openings.lineFor(pairIndex);

            // Same opening, both colours, so the opening's own advantage cancels.
            Game.Outcome first = Game.play(a, b, opening, goArgs, timeoutMillis, maxPlies);
            Game.Outcome second = Game.play(b, a, opening, goArgs, timeoutMillis, maxPlies);

            double scoreA = first.result().whiteScore() + (1.0 - second.result().whiteScore());
            int bucket = (int) Math.round(scoreA * 2);      // 0..4 in half-points

            Reply resultReply = postReply("/result", "key " + key + "\nbucket " + bucket
                    + "\ndetail " + first.result() + "/" + second.result() + "\n");

            if (resultReply.unexpected()) {
                // The status was discarded here, so a 400 or a 500 parsed as an
                // acknowledgement with no "counted" key and the worker moved on
                // with the pair unreported and nothing in the log. The retry
                // loop inside send() only ever covered IOException.
                System.err.printf("%s: /result returned %d for %s, retrying once%n",
                        id, resultReply.status(), key);
                Thread.sleep(RETRY_MILLIS);
                resultReply = postReply("/result", "key " + key + "\nbucket " + bucket
                        + "\ndetail " + first.result() + "/" + second.result() + "\n");
                if (resultReply.unexpected()) {
                    System.err.printf("%s: giving up on %s after %d%n",
                            id, key, resultReply.status());
                    continue;
                }
            }

            played++;
            Map<String, String> ack = parse(resultReply.body());
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
    private static final long FIRST_RETRY_MILLIS = 1_000;
    private static final long MAX_RETRY_MILLIS = 30_000;
    private static final long DEFAULT_GIVE_UP_MILLIS = 15 * 60 * 1_000L;

    /**
     * A lease response, with the two empty cases kept distinct.
     *
     * Naming them is the point. When "no job right now" and "no job ever again"
     * were both a null body, it took one careless equality check to make a worker
     * quit while another worker's jobs were still waiting to be reclaimed.
     */
    record Reply(int status, String body) {
        boolean retryLater() { return status == 503; }
        boolean over()       { return status == 204; }
        boolean ok()         { return status == 200; }

        /**
         * Anything else: a proxy's 502, a captive portal's 302, a 400 from a
         * protocol mismatch. These were handled by neither branch, so a lease
         * response fell through to parsing a body that was not a job and the
         * worker died on NumberFormatException, and a result response was
         * treated as an acknowledgement so the pair was silently never reported.
         */
        boolean unexpected() { return !ok() && !retryLater() && !over(); }
    }

    /**
     * Per-request deadline, which connectTimeout does not provide.
     *
     * connectTimeout covers the TCP handshake only. A connection that is
     * accepted and then never answered (a laptop that slept leaving a half-open
     * socket, or the coordinator's single-threaded executor blocked inside a
     * slow write) left http.send blocked forever. `waited` never advanced, so
     * the documented GIVE_UP_MILLIS could not fire and the worker hung silently
     * for good, which is precisely the outcome its retry loop exists to prevent.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    Reply get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(base.resolve(path))
                .timeout(REQUEST_TIMEOUT).GET().build(), "GET " + path);
    }

    private Reply postReply(String path, String body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(base.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), "POST " + path);
    }

    /**
     * Sends, retrying through transient network failure with exponential backoff.
     *
     * Without this a worker dies on the first dropped packet, which over a long run
     * is not an edge case but a certainty: wifi drops, the coordinator gets
     * restarted, a laptop's interface cycles. A run left going overnight would find
     * every worker dead by morning and the coordinator patiently holding leases for
     * machines that stopped existing hours ago.
     *
     * <p>Retrying a {@code /result} POST is safe for a reason that already exists:
     * the coordinator deduplicates by job key, because lease expiry made duplicate
     * submissions certain. At-least-once delivery from a retrying worker lands in
     * exactly the same path. The dedupe was not built for this and covers it anyway.
     *
     * <p>Giving up after {@link #GIVE_UP_MILLIS} is deliberate. A worker that cannot
     * reach the coordinator for that long is not in a blip, and a process that exits
     * is easier to notice and restart than one looping silently forever.
     */
    private Reply send(HttpRequest request, String what) throws IOException, InterruptedException {
        long delay = FIRST_RETRY_MILLIS;
        long waited = 0;
        IOException last = null;

        while (waited < giveUpMillis) {
            try {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (waited > 0) System.out.printf("%s: %s recovered after %ds%n", id, what, waited / 1000);
                return new Reply(r.statusCode(), r.body());
            } catch (IOException e) {
                last = e;
                Thread.sleep(delay);
                waited += delay;
                delay = Math.min(delay * 2, MAX_RETRY_MILLIS);
            }
        }
        throw new IOException(what + " unreachable for " + (giveUpMillis / 1000) + "s", last);
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
