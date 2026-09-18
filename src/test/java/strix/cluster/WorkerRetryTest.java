package strix.cluster;

import org.junit.jupiter.api.Test;
import strix.harness.MatchRunner.EngineSpec;
import strix.harness.Sprt;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A worker has to outlive the coordinator being unreachable.
 *
 * Before this, one dropped packet threw straight out of {@code run()} and killed
 * the process. Over a long run that is not an edge case: wifi cycles, the
 * coordinator gets restarted, a laptop's interface flaps. A run left going
 * overnight would be found dead in the morning, with the coordinator still holding
 * leases for machines that stopped existing hours earlier.
 *
 * No chess engines are started. The engines open in {@code run()}; the transport
 * is what is under test.
 */
final class WorkerRetryTest {

    private static Worker workerPointedAt(int port, long giveUpMillis) {
        EngineSpec unused = EngineSpec.of("unused", List.of("true"));
        return new Worker("t", "http://localhost:" + port, unused, unused,
                "nodes 1", 1_000, 10, giveUpMillis);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @Test
    void survivesTheCoordinatorBeingDownWhenItAsks() throws Exception {
        int port = freePort();

        // Nothing is listening yet. The worker must wait rather than die.
        Thread startLate = new Thread(() -> {
            try {
                Thread.sleep(2_000);
                Coordinator c = new Coordinator(Sprt.standard(),
                        Files.createTempFile("late", ".tsv"), 60_000, 4);
                c.load();
                new ClusterServer(c, port).start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        startLate.start();

        try (Worker w = workerPointedAt(port, 30_000)) {
            Worker.Reply r = w.get("/status");      // connection refused, then refused, then works
            assertEquals(200, r.status(), "should have retried until the coordinator came up");
            assertTrue(r.body().contains("counted"));
        }
        startLate.join();
    }

    @Test
    void givesUpEventuallyRatherThanLoopingForever() throws Exception {
        // Nothing will ever listen here. The worker must not spin silently forever;
        // a process that exits is easier to notice and restart than one that does not.
        int port = freePort();
        try (Worker w = workerPointedAt(port, 3_000)) {
            long started = System.currentTimeMillis();
            assertThrows(IOException.class, () -> w.get("/status"));
            long elapsed = System.currentTimeMillis() - started;
            assertTrue(elapsed >= 1_000, "gave up instantly, so it never retried at all");
        }
    }
}
