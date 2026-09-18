package strix.cluster;

import org.junit.jupiter.api.Test;
import strix.harness.Sprt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same accounting guarantees, but over a real socket.
 *
 * {@link CoordinatorTest} proves the logic. This proves the wire: that a lease
 * survives serialisation, that "none right now" (503) and "none ever again" (204)
 * stay distinguishable, and that a duplicate is reported as a successful request
 * that did not count, which is the distinction a worker has to act on.
 *
 * No chess engines are started. A pair of real games takes seconds, and nothing
 * being tested here depends on what the games contained.
 */
final class ClusterServerTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private record Fixture(Coordinator coordinator, ClusterServer server, AtomicLong clock, String base) {}

    private Fixture start(int maxPairs, long leaseMillis) throws Exception {
        Path log = Files.createTempFile("cluster", ".tsv");
        AtomicLong clock = new AtomicLong(1_000);
        Coordinator c = new Coordinator(Sprt.standard(), log, leaseMillis, maxPairs, clock::get);
        c.load();
        ClusterServer s = new ClusterServer(c, 0);      // port 0: let the OS pick
        s.start();
        return new Fixture(c, s, clock, "http://localhost:" + s.port());
    }

    @Test
    void leaseThenSubmitOverHttp() throws Exception {
        Fixture f = start(2, 60_000);
        try (ClusterServer ignored = f.server()) {
            HttpResponse<String> lease = get(f.base() + "/lease?worker=w1");
            assertEquals(200, lease.statusCode());

            Map<String, String> job = parse(lease.body());
            assertNotNull(job.get("key"));
            assertNotNull(job.get("opening"));

            HttpResponse<String> ack = post(f.base() + "/result",
                    "key " + job.get("key") + "\nbucket 2\ndetail 1/2-1/2,1/2-1/2\n");
            assertEquals(200, ack.statusCode());
            assertEquals("true", parse(ack.body()).get("counted"));
            assertEquals(1, f.coordinator().sprt().pairCount());
        }
    }

    @Test
    void aDuplicateIsA200ThatDidNotCount() throws Exception {
        Fixture f = start(1, 5_000);
        try (ClusterServer ignored = f.server()) {
            String key = parse(get(f.base() + "/lease?worker=slow").body()).get("key");

            f.clock().addAndGet(5_001);                 // slow worker's lease lapses
            String reissued = parse(get(f.base() + "/lease?worker=fresh").body()).get("key");
            assertEquals(key, reissued);

            assertEquals("true", parse(post(f.base() + "/result",
                    "key " + key + "\nbucket 2\ndetail d\n").body()).get("counted"));

            HttpResponse<String> second = post(f.base() + "/result",
                    "key " + reissued + "\nbucket 2\ndetail d\n");
            assertEquals(200, second.statusCode(), "the worker did nothing wrong");
            assertEquals("false", parse(second.body()).get("counted"));

            assertEquals(1, f.coordinator().sprt().pairCount(), "counted once, not twice");
        }
    }

    /**
     * Regression: an empty queue with work still leased out must NOT look like the
     * end of the run.
     *
     * When both cases were a 204, the surviving workers quit the moment a dead
     * worker's jobs were the only ones left. The lease then expired with nobody
     * around to claim it, and the run ended quietly with fewer pairs than it
     * reported needing. Nothing errored.
     */
    @Test
    void anEmptyQueueIsNotTheEndOfTheRun() throws Exception {
        Fixture f = start(1, 5_000);
        try (ClusterServer ignored = f.server()) {
            get(f.base() + "/lease?worker=holder");        // the only job is now leased

            HttpResponse<String> busy = get(f.base() + "/lease?worker=other");
            assertEquals(503, busy.statusCode(), "come back later, not give up");
            assertEquals("2", busy.headers().firstValue("Retry-After").orElse(null));

            f.clock().addAndGet(5_001);                    // holder goes silent
            assertEquals(200, get(f.base() + "/lease?worker=other").statusCode(),
                    "the abandoned job is reclaimable, which is why waiting mattered");
        }
    }

    @Test
    void theRunIsOverOnceTheBudgetIsSpent() throws Exception {
        Fixture f = start(1, 60_000);
        try (ClusterServer ignored = f.server()) {
            String key = parse(get(f.base() + "/lease?worker=w1").body()).get("key");
            post(f.base() + "/result", "key " + key + "\nbucket 2\ndetail d\n");

            HttpResponse<String> over = get(f.base() + "/lease?worker=w1");
            assertEquals(204, over.statusCode(), "budget spent, this really is terminal");
            assertTrue(over.body().isEmpty(), "a 204 carries no body");
        }
    }

    @Test
    void malformedResultIsRejected() throws Exception {
        Fixture f = start(1, 60_000);
        try (ClusterServer ignored = f.server()) {
            assertEquals(400, post(f.base() + "/result", "key 0:0\n").statusCode());
            assertEquals(400, post(f.base() + "/result", "key 0:0\nbucket banana\n").statusCode());
        }
    }

    @Test
    void statusReportsTheAccounting() throws Exception {
        Fixture f = start(3, 60_000);
        try (ClusterServer ignored = f.server()) {
            String key = parse(get(f.base() + "/lease?worker=w1").body()).get("key");
            post(f.base() + "/result", "key " + key + "\nbucket 2\ndetail d\n");

            Map<String, String> status = parse(get(f.base() + "/status").body());
            assertEquals("1", status.get("counted"));
            assertEquals("2", status.get("pending"));
            assertEquals("0", status.get("inflight"));
            assertEquals("0", status.get("duplicates"));
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> out = new HashMap<>();
        for (String line : body.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            int space = t.indexOf(' ');
            if (space > 0) out.put(t.substring(0, space), t.substring(space + 1));
        }
        return out;
    }
}
