package strix.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Puts {@link Coordinator} on a socket.
 *
 * <h2>Why plain text and not JSON</h2>
 * The messages are three flat fields with known shapes. This project already
 * speaks a line-oriented text protocol to every engine it drives, so the cluster
 * speaking the same shape means one fewer format in the codebase and no parser to
 * get wrong. It is also trivially debuggable:
 *
 * <pre>
 *   curl "localhost:9000/lease?worker=manual"
 *   curl -d $'key 0:0\nbucket 2\ndetail manual' localhost:9000/result
 *   curl localhost:9000/status
 * </pre>
 *
 * <h2>Why a single-threaded executor</h2>
 * Every request takes the coordinator's lock anyway, so concurrency here would buy
 * nothing but a harder correctness argument. A pair of games takes seconds; the
 * bookkeeping around it takes microseconds. The bottleneck is never this server.
 */
public final class ClusterServer implements AutoCloseable {

    private final Coordinator coordinator;
    private final HttpServer http;
    private final ExecutorService requests;

    public ClusterServer(Coordinator coordinator, int port) throws IOException {
        this.coordinator = coordinator;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        this.requests = Executors.newSingleThreadExecutor();
        http.setExecutor(requests);
        http.createContext("/lease", this::handleLease);
        http.createContext("/result", this::handleResult);
        http.createContext("/status", this::handleStatus);
    }

    public void start() { http.start(); }

    public int port() { return http.getAddress().getPort(); }

    @Override
    public void close() {
        http.stop(0);
        // HttpServer.stop() does NOT touch an executor you supplied, and these
        // threads are not daemons. Without this the coordinator prints its final
        // verdict and then hangs forever instead of exiting.
        requests.shutdownNow();
    }

    /** 200 with a job, 503 if the queue is momentarily empty, 204 when the run is over. */
    private void handleLease(HttpExchange x) throws IOException {
        if (!"GET".equals(x.getRequestMethod())) { send(x, 405, ""); return; }

        String worker = query(x).getOrDefault("worker", "anonymous");
        Optional<Job> job = coordinator.lease(worker);
        if (job.isEmpty()) {
            // 204 is terminal, 503 is "try again shortly". See Coordinator.exhausted().
            if (coordinator.exhausted()) send(x, 204, "");
            else { x.getResponseHeaders().add("Retry-After", "2"); send(x, 503, "retry\n"); }
            return;
        }

        Job j = job.get();
        send(x, 200, "key " + j.key() + "\nopening " + j.openingIndex() + "\npair " + j.pairIndex() + "\n");
    }

    /**
     * Accepts a finished pair. Replies whether it counted.
     *
     * A duplicate is a 200, not an error. The worker did nothing wrong: it was
     * handed the job, it did the work, and it reported honestly. Whether the
     * result was needed is the coordinator's business, and returning 4xx here
     * would invite a worker to retry something that must never be retried.
     */
    private void handleResult(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) { send(x, 405, ""); return; }

        Map<String, String> body = lines(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String key = body.get("key");
        String bucket = body.get("bucket");
        if (key == null || bucket == null) { send(x, 400, "need key and bucket\n"); return; }

        boolean countedIt;
        try {
            countedIt = coordinator.submit(key, Integer.parseInt(bucket.trim()),
                    body.getOrDefault("detail", ""));
        } catch (NumberFormatException e) {
            send(x, 400, "bucket must be an integer\n");
            return;
        }
        send(x, 200, "counted " + countedIt + "\nstop " + coordinator.stopped() + "\n");
    }

    private void handleStatus(HttpExchange x) throws IOException {
        Coordinator.Status s = coordinator.status();
        send(x, 200, """
                counted %d
                pending %d
                inflight %d
                duplicates %d
                expired %d
                elo %.2f
                llr %.3f
                verdict %s
                """.formatted(s.counted(), s.pending(), s.inFlight(), s.duplicates(),
                              s.expired(), s.elo(), s.llr(), s.verdict()));
    }

    private static void send(HttpExchange x, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // A 204 carries no body by definition, and sending a length with it makes
        // some clients hang waiting for bytes that never come.
        x.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
        if (code != 204) {
            try (OutputStream out = x.getResponseBody()) { out.write(bytes); }
        }
        x.close();
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> out = new HashMap<>();
        String raw = x.getRequestURI().getQuery();
        if (raw == null) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    /** "key value" per line, value may contain spaces. */
    private static Map<String, String> lines(String body) {
        Map<String, String> out = new HashMap<>();
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            int space = trimmed.indexOf(' ');
            if (space < 0) out.put(trimmed, "");
            else out.put(trimmed.substring(0, space), trimmed.substring(space + 1));
        }
        return out;
    }
}
