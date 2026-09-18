package strix.cluster;

import strix.harness.Openings;
import strix.harness.Sprt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Hands out pair jobs, collects results, and decides when the test has settled.
 *
 * This is {@code MatchRunner} with the in-process queue replaced by a network,
 * and that single change is the whole problem. A thread that dies takes the
 * process with it. A worker that dies leaves the coordinator running, holding a
 * job that may or may not ever come back.
 *
 * <h2>Leases, and what they cost</h2>
 * A job is handed out with an expiry. If no result arrives before it expires the
 * job returns to the queue and goes to someone else. The original worker is never
 * told, because the coordinator has no way to reach it and no way to know whether
 * it is dead or merely slow. Over a network those two look identical: silence.
 *
 * The cost is stated plainly: <b>a result can arrive after its job has been
 * reassigned</b>, so the same key can be submitted twice. That is not an edge
 * case to be engineered away, it is the direct consequence of choosing a timeout,
 * and the only honest response is to make the second submission harmless.
 *
 * <h2>Dedupe, and the property that makes it safe</h2>
 * The append-only log is the source of truth for "already counted". {@code counted}
 * is its in-memory projection, rebuilt from the log at startup by
 * {@link #replayLog()}. A result whose key is already in that set is dropped
 * before it reaches the SPRT.
 *
 * Dropping rather than reconciling is only safe because games run at fixed nodes
 * per move rather than on a clock (ADR 0011). Two workers computing the same pair
 * produce the <i>identical</i> result, so there is never a question of which one
 * is real. Under wall-clock time control the two could disagree and this would be
 * a merge problem instead of a drop.
 *
 * <h2>Why counting twice would be invisible</h2>
 * {@code Sprt.record} has no idea a pair is a repeat. A double-counted pair moves
 * the log-likelihood ratio exactly as far as a real one, so the test would stop
 * early or late on evidence that does not exist, and report an Elo number with no
 * outward sign of being wrong. Silent corruption of the only output the harness
 * produces is the failure this class exists to prevent.
 */
public final class Coordinator {

    /** Who holds a job, and until when. */
    public record Lease(String workerId, long expiresAtMillis) {}

    /** What the coordinator knows, for /status and for tests. */
    public record Status(long counted, int pending, int inFlight, long duplicates,
                         long expired, double elo, double llr, Sprt.Verdict verdict) {}

    private final Object lock = new Object();

    private final Deque<Job> pending = new ArrayDeque<>();
    private final Map<String, Lease> inFlight = new HashMap<>();
    private final Set<String> counted = new HashSet<>();

    private final Sprt sprt;
    private final Path log;
    private final long leaseMillis;
    private final int maxPairs;

    /** Injected so lease expiry is testable without sleeping through it. */
    private final LongSupplier clock;

    private long duplicates;
    private long expired;
    private boolean stopped;

    public Coordinator(Sprt sprt, Path log, long leaseMillis, int maxPairs) {
        this(sprt, log, leaseMillis, maxPairs, System::currentTimeMillis);
    }

    public Coordinator(Sprt sprt, Path log, long leaseMillis, int maxPairs, LongSupplier clock) {
        this.sprt = sprt;
        this.log = log;
        this.leaseMillis = leaseMillis;
        this.maxPairs = maxPairs;
        this.clock = clock;
    }

    /**
     * Rebuilds {@code counted} and the SPRT from the log, then queues everything
     * still outstanding. Resuming a half-finished run costs the pairs that were in
     * flight when the coordinator died, not the ones already on disk.
     */
    public void load() throws IOException {
        synchronized (lock) {
            counted.clear();
            pending.clear();
            inFlight.clear();

            if (Files.exists(log)) {
                for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String key = line.substring(0, tab);
                    if (!counted.add(key)) continue;
                    int second = line.indexOf('\t', tab + 1);
                    String bucket = line.substring(tab + 1, second < 0 ? line.length() : second);
                    try {
                        sprt.record(Integer.parseInt(bucket.trim()));
                    } catch (NumberFormatException ignored) {
                        // A truncated final line is expected after a hard kill. The key
                        // still counts as done; only its observation is lost.
                    }
                }
            }

            for (int p = 0; counted.size() + pending.size() < maxPairs; p++) {
                Job job = new Job(p % Openings.size(), p);
                if (!counted.contains(job.key())) pending.addLast(job);
            }
        }
    }

    /**
     * Hands a job to a worker, or returns empty when there is nothing to give.
     *
     * Empty means one of three things: the test has settled, the pair budget is
     * exhausted, or every remaining job is currently leased to somebody else.
     * The first two are permanent and the third is not, so callers must consult
     * {@link #exhausted()} before concluding there is no more work.
     */
    public Optional<Job> lease(String workerId) {
        synchronized (lock) {
            sweepExpired();
            if (stopped || counted.size() >= maxPairs) return Optional.empty();

            Job job = pending.pollFirst();
            if (job == null) return Optional.empty();

            inFlight.put(job.key(), new Lease(workerId, clock.getAsLong() + leaseMillis));
            return Optional.of(job);
        }
    }

    /**
     * Records a finished pair.
     *
     * @return true if this result was counted, false if it was a duplicate and
     *         was dropped. The worker is told which, but only so the demo can show
     *         it happening; nothing about correctness depends on the worker's
     *         reaction, because the coordinator has already made the decision.
     */
    public boolean submit(String key, int bucket, String detail) throws IOException {
        synchronized (lock) {
            // The job is resolved regardless of who held the lease. If a late
            // result from a previously-expired worker arrives first, the current
            // holder's submission is the one that gets dropped below.
            inFlight.remove(key);

            if (!counted.add(key)) {
                duplicates++;
                return false;
            }

            try (BufferedWriter w = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(key + "\t" + bucket + "\t" + detail);
                w.newLine();
            }   // durable before it counts, same ordering MatchRunner uses

            sprt.record(bucket);

            if (sprt.pairCount() >= Sprt.MIN_PAIRS && sprt.verdict() != Sprt.Verdict.CONTINUE) {
                stopped = true;
            }
            if (counted.size() >= maxPairs) stopped = true;
            return true;
        }
    }

    /**
     * Returns expired leases to the queue.
     *
     * Jobs go back on the FRONT. A job that has already failed once is the most
     * likely to be the reason the run is waiting at the end, so it should be
     * retried before fresh work rather than after all of it.
     */
    private void sweepExpired() {
        long now = clock.getAsLong();
        var it = inFlight.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().expiresAtMillis() > now) continue;
            it.remove();
            expired++;
            if (!counted.contains(e.getKey())) pending.addFirst(Job.fromKey(e.getKey()));
        }
    }

    public boolean stopped() {
        synchronized (lock) { return stopped; }
    }

    /**
     * True when there will never be another job, as opposed to merely none right now.
     *
     * A worker has to be able to tell those apart. An empty queue with jobs still
     * leased out means "come back shortly": if every worker treated that as the end
     * of the run, the first worker to die would strand its jobs permanently, because
     * everyone else would have gone home before the lease expired. That failure is
     * silent and terminal, and it looks exactly like a finished run except the
     * counts are short.
     */
    public boolean exhausted() {
        synchronized (lock) { return stopped || counted.size() >= maxPairs; }
    }

    public Status status() {
        synchronized (lock) {
            return new Status(counted.size(), pending.size(), inFlight.size(),
                    duplicates, expired, sprt.elo(), sprt.llr(), sprt.verdict());
        }
    }

    public Sprt sprt() { return sprt; }
}
