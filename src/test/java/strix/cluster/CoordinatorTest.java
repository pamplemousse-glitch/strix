package strix.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import strix.harness.Sprt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The accounting guarantees, tested without a network and without sleeping.
 *
 * Time is injected, so lease expiry is a variable assignment rather than a wait.
 * That is the difference between a suite that runs in milliseconds on every push
 * and one that nobody runs.
 *
 * Every pair here scores 2 (a drawn pair). A drawn pair carries no evidence
 * either way, so the SPRT never crosses a bound and never stops the run out from
 * under a test that is measuring something else.
 */
final class CoordinatorTest {

    private static final int DRAWN_PAIR = 2;

    private AtomicLong now;

    private Coordinator coordinator(Path log, long leaseMillis, int maxPairs) throws IOException {
        now = new AtomicLong(1_000);
        Coordinator c = new Coordinator(Sprt.standard(), log, leaseMillis, maxPairs, now::get);
        c.load();
        return c;
    }

    @Test
    void handsOutEachJobOnce() throws IOException, InterruptedException {
        Coordinator c = coordinator(Files.createTempFile("m", ".tsv"), 5_000, 4);

        Set<String> handedOut = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            Optional<Job> job = c.lease("w1");
            assertTrue(job.isPresent(), "expected a job at index " + i);
            assertTrue(handedOut.add(job.get().key()), "same job handed out twice");
        }
        assertTrue(c.lease("w1").isEmpty(), "everything is leased, nothing left to give");
    }

    @Test
    void expiredLeaseGoesToSomeoneElse() throws IOException {
        Coordinator c = coordinator(Files.createTempFile("m", ".tsv"), 5_000, 1);

        Job first = c.lease("worker-a").orElseThrow();
        assertTrue(c.lease("worker-b").isEmpty(), "still leased, b gets nothing");

        now.addAndGet(5_001);                      // worker-a goes silent past its lease

        Job reissued = c.lease("worker-b").orElseThrow();
        assertEquals(first.key(), reissued.key(), "the abandoned job should be reassigned");
        assertEquals(1, c.status().expired());
    }

    /**
     * The one that matters.
     *
     * Worker A is declared dead, its job goes to worker B, and then A turns out to
     * have been alive all along and submits. Both results are real and identical,
     * because fixed-node games are deterministic. Exactly one may count.
     */
    @Test
    void aLateResultAndItsReplacementCountOnlyOnce() throws IOException {
        Path log = Files.createTempFile("m", ".tsv");
        Coordinator c = coordinator(log, 5_000, 1);

        Job job = c.lease("worker-a").orElseThrow();
        now.addAndGet(5_001);
        Job sameJob = c.lease("worker-b").orElseThrow();
        assertEquals(job.key(), sameJob.key());

        assertTrue(c.submit(job.key(), DRAWN_PAIR, "1/2-1/2,1/2-1/2"),
                "worker-a was slow, not dead; its result is real and arrives first");
        assertFalse(c.submit(sameJob.key(), DRAWN_PAIR, "1/2-1/2,1/2-1/2"),
                "worker-b computed the same pair; the second copy must be dropped");

        assertEquals(1, c.sprt().pairCount(), "the statistical test saw exactly one pair");
        assertEquals(1, c.status().duplicates());
        assertEquals(1, Files.readAllLines(log).size(), "one line on disk, not two");
    }

    /**
     * The chaos case, in deterministic form: a worker takes jobs and never returns
     * any of them, while a second worker does the work. Every job must land exactly
     * once and the totals must be exact.
     */
    @Test
    void everyJobIsCountedExactlyOnceWhenAWorkerDisappears() throws IOException {
        int jobs = 12;
        Path log = Files.createTempFile("m", ".tsv");
        Coordinator c = coordinator(log, 1_000, jobs);

        Set<String> submitted = new HashSet<>();
        int deadTakes = 0;

        // The dead worker grabs a job and drops it. The live worker finishes one.
        // Alternating means roughly half the jobs are abandoned at least once.
        // Bounded. Unbounded, any regression that stops a job being counted
        // hangs the build instead of failing it, and a hung CI job is much
        // harder to read than a red one.
        int guard = 0;
        while (c.status().counted() < jobs) {
            if (++guard > 10_000) {
                org.junit.jupiter.api.Assertions.fail(
                        "stuck at " + c.status().counted() + "/" + jobs + " after " + guard + " turns");
            }
            if (deadTakes < 5 && c.lease("dead-worker").isPresent()) {
                deadTakes++;
                now.addAndGet(1_001);              // its lease lapses immediately
                continue;
            }
            Optional<Job> job = c.lease("live-worker");
            if (job.isEmpty()) { now.addAndGet(1_001); continue; }
            boolean countedIt = c.submit(job.get().key(), DRAWN_PAIR, "d");
            assertEquals(submitted.add(job.get().key()), countedIt,
                    "counted exactly when the key was new");
        }

        assertEquals(jobs, c.status().counted());
        assertEquals(jobs, c.sprt().pairCount(), "no pair counted twice, none lost");
        assertEquals(jobs, Files.readAllLines(log).size());
        assertTrue(c.status().expired() >= 5, "the dead worker's leases did expire");
    }

    @Test
    void resumingSkipsWorkAlreadyOnDisk() throws IOException {
        Path log = Files.createTempFile("m", ".tsv");
        Coordinator first = coordinator(log, 5_000, 6);
        for (int i = 0; i < 4; i++) {
            first.submit(first.lease("w").orElseThrow().key(), DRAWN_PAIR, "d");
        }

        Coordinator resumed = coordinator(log, 5_000, 6);
        assertEquals(4, resumed.status().counted(), "rebuilt from the log");
        assertEquals(4, resumed.sprt().pairCount(), "and so was the statistical test");
        assertEquals(2, resumed.status().pending(), "only the unfinished pairs are queued");
    }

    @Test
    void aTruncatedFinalLineStillCountsAsDone() throws IOException {
        Path log = Files.createTempFile("m", ".tsv");
        Files.writeString(log, "0:0\t2\td\n1:1\t");   // killed mid-write

        Coordinator c = coordinator(log, 5_000, 4);
        assertEquals(2, c.status().counted(), "both keys are done");
        assertEquals(1, c.sprt().pairCount(), "but only the intact one is an observation");
    }
}
