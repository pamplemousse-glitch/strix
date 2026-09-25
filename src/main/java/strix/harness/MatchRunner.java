package strix.harness;

import strix.core.GameResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs games in parallel and feeds the results to a sequential statistical test.
 *
 * That sentence is the whole design problem. Production is parallel, consumption
 * is sequential, and the two have to be reconciled deliberately rather than by
 * accident.
 *
 * <h2>The job model</h2>
 * A job is one PAIR of games: the same opening played twice with colours reversed.
 * Its key is {@code (openingIndex, pairIndex)}, which is idempotent, so a result
 * written once is never recomputed. Results append to a durable log, and resuming
 * means reading that log and skipping keys already present. Dying at pair 800 of
 * 1000 costs the pairs in flight, not the 800 already finished.
 *
 * <h2>Batching, and what happens at the stopping boundary</h2>
 * The pentanomial model scores a PAIR jointly, so half a pair is not an
 * observation it can consume. Pairs are therefore atomic, and results are consumed
 * in batches of {@link #BATCH_PAIRS}.
 *
 * When the SPRT crosses a bound there are pairs still running. They are DISCARDED
 * rather than awaited. This introduces a small bias, and it is worth naming rather
 * than hiding: slow pairs are not a random sample. They skew toward longer, closer,
 * more drawish games. Waiting for them instead would mean accepting data that
 * arrived after the stopping rule fired, which weakens the error-rate guarantee the
 * whole test rests on. Neither choice is free. This one matches Fishtest.
 */
public final class MatchRunner {

    public static final int BATCH_PAIRS = 4;   // 8 games, matching Fishtest's reporting granularity

    public record EngineSpec(String name, List<String> command, Map<String, String> options) {
        public static EngineSpec of(String name, List<String> command) {
            return new EngineSpec(name, command, Map.of());
        }
        public EngineSpec with(String key, String value) {
            var m = new LinkedHashMap<>(options);
            m.put(key, value);
            return new EngineSpec(name, command, m);
        }
    }

    public record Config(EngineSpec engineA, EngineSpec engineB, String goArgs,
                         long timeoutMillis, int maxPlies, int workers, int maxPairs,
                         Path log) {}

    /** One pair of games: the same opening, both colours. */
    private record PairJob(int openingIndex, int pairIndex) {
        // The pair index alone. openingIndex is pairIndex % Openings.size(), so
        // the old "opening:pair" key baked the book size into the identity: a
        // book edit between a crash and a resume renamed every key past the old
        // size, and those pairs were both replayed from the log and re-run.
        String key() { return String.valueOf(pairIndex); }

        /** Legacy keys normalise to the new form so existing logs still resume. */
        static String normalise(String key) {
            String k = key.trim();
            int colon = k.indexOf(':');
            return colon < 0 ? k : k.substring(colon + 1);
        }
    }

    private record PairResult(String key, int bucket, String detail) {}

    private final Config config;
    private final Sprt sprt;
    private final AtomicBoolean finished = new AtomicBoolean();

    /**
     * Workers still alive. The collector needs this to tell a slow pair from a
     * dead fleet: both look like a poll timeout, and only one of them means the
     * match is over.
     */
    private final java.util.concurrent.atomic.AtomicInteger activeWorkers =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Engine restarts, reported at the end so a degraded run is never silent. */
    private final java.util.concurrent.atomic.AtomicInteger workerRestarts =
            new java.util.concurrent.atomic.AtomicInteger();

    public MatchRunner(Config config, Sprt sprt) {
        this.config = config;
        this.sprt = sprt;
    }

    public Sprt run() throws IOException, InterruptedException {
        Set<String> done = replayLog();
        if (!done.isEmpty()) {
            System.out.printf("resuming: %d pairs already in %s%n", done.size(), config.log());
        }

        List<PairJob> jobs = new ArrayList<>();
        for (int p = 0; jobs.size() < config.maxPairs(); p++) {
            PairJob job = new PairJob(p % Openings.size(), p);
            if (!done.contains(job.key())) jobs.add(job);
        }

        BlockingQueue<PairJob> queue = new LinkedBlockingQueue<>(jobs);
        BlockingQueue<PairResult> results = new LinkedBlockingQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(config.workers());
        for (int w = 0; w < config.workers(); w++) {
            final int id = w;
            activeWorkers.incrementAndGet();
            pool.submit(() -> {
                try {
                    worker(id, queue, results);
                } finally {
                    activeWorkers.decrementAndGet();
                }
            });
        }

        try (BufferedWriter logWriter = Files.newBufferedWriter(config.log(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            // Non-zero so a resumed run consults the test on its first result
            // instead of playing a whole batch past a bound it had already
            // crossed. replayLog feeds sprt but cannot feed this counter.
            int sinceReport = BATCH_PAIRS - 1;
            int quietPolls = 0;
            while (!finished.get()) {
                PairResult r = results.poll(60, TimeUnit.SECONDS);
                if (r == null) {
                    // A timeout is not "nothing in flight, nothing queued",
                    // which is what the old comment here claimed. It is also
                    // what a pair slower than 60s looks like, and what every
                    // worker having died looks like. Only the last is terminal,
                    // and ending the match on the other two truncated the sample
                    // while Main went on to print a confident verdict.
                    if (pool.isTerminated() || activeWorkers.get() == 0) {
                        System.err.println("all workers gone after " + sprt.pairCount() + " pairs");
                        break;
                    }
                    if (++quietPolls % 5 == 0) {
                        System.err.printf("no result in %d minutes, %d workers alive, %d pairs done%n",
                                quietPolls, activeWorkers.get(), sprt.pairCount());
                    }
                    continue;
                }
                quietPolls = 0;

                logWriter.write(r.key() + "\t" + r.bucket() + "\t" + r.detail());
                logWriter.newLine();
                logWriter.flush();                          // durable before it counts

                sprt.record(r.bucket());
                sinceReport++;

                // Consume in batches: the test is only consulted on a batch boundary.
                if (sinceReport >= BATCH_PAIRS) {
                    sinceReport = 0;
                    System.out.println("  " + sprt);
                    if (sprt.verdict() != Sprt.Verdict.CONTINUE) {
                        finished.set(true);                 // in-flight pairs are discarded
                        break;
                    }
                }
                if (sprt.pairCount() >= config.maxPairs()) break;
            }
        } finally {
            finished.set(true);
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            if (workerRestarts.get() > 0) {
                System.err.println("NOTE: " + workerRestarts.get()
                        + " engine restart(s) during this run");
            }
        }
        return sprt;
    }

    /**
     * Plays pairs until the queue is empty or the run ends, restarting its
     * engines if they die.
     *
     * The IOException catch used to sit outside the loop, so one engine timeout
     * retired the worker permanently. Parallelism degraded silently, and the
     * pair it had already dequeued was never played by anyone. With enough
     * workers gone the collector's poll timed out and the match ended early
     * looking complete.
     */
    private void worker(int id, BlockingQueue<PairJob> queue, BlockingQueue<PairResult> results) {
        try {
            while (!finished.get()) {
                try (UciEngine a = open(config.engineA()); UciEngine b = open(config.engineB())) {
                    while (!finished.get()) {
                        PairJob job = queue.poll();
                        if (job == null) return;
                        try {
                            playPair(a, b, job, results);
                        } catch (IOException e) {
                            // Put it back before reopening: an engine that died
                            // mid-pair leaves a job nobody has played.
                            queue.put(job);
                            throw e;
                        }
                    }
                    return;
                } catch (IOException e) {
                    if (finished.get()) return;
                    System.err.println("worker " + id + ": " + e.getMessage() + ", restarting engines");
                    workerRestarts.incrementAndGet();
                    Thread.sleep(500);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void playPair(UciEngine a, UciEngine b, PairJob job, BlockingQueue<PairResult> results)
            throws IOException, InterruptedException {
        String opening = Openings.lineFor(job.pairIndex());

        // Same opening, both colours. This cancels the opening's built-in
        // advantage, which is what makes an unbalanced book safe to use.
        Game.Outcome first = Game.play(a, b, opening, config.goArgs(),
                config.timeoutMillis(), config.maxPlies());
        if (finished.get()) return;
        Game.Outcome second = Game.play(b, a, opening, config.goArgs(),
                config.timeoutMillis(), config.maxPlies());

        double aScore = first.result().whiteScore() + (1.0 - second.result().whiteScore());
        int bucket = (int) Math.round(aScore * 2);   // 0..4 in half-points
        results.put(new PairResult(job.key(), bucket,
                first.result() + "/" + second.result()));
    }

    private UciEngine open(EngineSpec spec) throws IOException {
        UciEngine e = new UciEngine(spec.name(), spec.command());
        for (var entry : spec.options().entrySet()) e.setOption(entry.getKey(), entry.getValue());
        return e;
    }

    /** Idempotency: every key already in the log is a pair that will not be replayed. */
    private Set<String> replayLog() throws IOException {
        Set<String> done = new HashSet<>();
        if (!Files.exists(config.log())) return done;
        for (String line : Files.readAllLines(config.log(), StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String key = PairJob.normalise(line.substring(0, tab));
            if (done.add(key)) {
                int second = line.indexOf('\t', tab + 1);
                String bucket = line.substring(tab + 1, second < 0 ? line.length() : second);
                // Range, not just parseability: Sprt.record indexes an array of
                // 5, so a garbled bucket threw an uncaught AIOOBE and killed the
                // resume rather than skipping one line.
                try {
                    int v = Integer.parseInt(bucket.trim());
                    if (v >= 0 && v <= 4) sprt.record(v);
                    else System.err.println("ignoring out-of-range bucket " + v + " for " + key);
                } catch (NumberFormatException ignored) { }
            }
        }
        return done;
    }
}
