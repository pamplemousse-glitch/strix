package strix.tune;

import strix.core.*;
import strix.eval.Psqt;
import strix.eval.Tunable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Texel tuning: fit the evaluation parameters to real game outcomes.
 *
 * <h2>The idea</h2>
 * A good evaluation should predict who wins. So take a pile of positions, each
 * labelled with how its game actually ended (1, 0.5 or 0), squash the evaluation
 * through a sigmoid into a predicted score, and adjust the parameters to minimise
 * the mean squared error between prediction and outcome.
 *
 * <pre>    E = (1/N) * sum( result - sigmoid(K * eval) )^2</pre>
 *
 * No games are played. This is curve fitting on a static file, which is why it
 * can move hundreds of parameters in minutes when SPRT would need tens of
 * thousands of games to settle a single one.
 *
 * <h2>K comes first</h2>
 * K scales centipawns into the sigmoid. It is not a free parameter to tune
 * alongside the others: it is fitted once against the CURRENT evaluation, so that
 * "error" means "mispredicts outcomes" rather than "is on a different scale".
 * Skipping this makes every later measurement meaningless.
 *
 * <h2>Why coordinate descent</h2>
 * Nudge one parameter, recompute the error, keep the change if it improved.
 * Repeat until a full pass changes nothing. It is slower than gradient descent
 * and it needs no derivatives, which matters because the evaluation is full of
 * branches (the tapered king term) that are not cleanly differentiable.
 */
public final class Texel {

    private record Sample(Board board, double result) {}

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args.length > 0 ? args[0] : "runs/positions.txt");
        Path out = Path.of(args.length > 1 ? args[1] : "runs/tuned.txt");
        int maxPasses = args.length > 2 ? Integer.parseInt(args[2]) : 8;

        List<Sample> samples = load(data);
        System.out.printf("loaded %,d positions%n", samples.size());
        if (samples.size() < 5_000) {
            System.out.println("that is thin for tuning; expect noise");
        }

        int[] params = Tunable.export();
        System.out.printf("tuning %d parameters%n%n", params.length);

        double k = fitK(samples);
        System.out.printf("K = %.4f  (fitted against the current eval, before tuning)%n", k);

        double best = error(samples, k);
        System.out.printf("starting error: %.8f%n%n", best);

        int step = 8;
        for (int pass = 1; pass <= maxPasses; pass++) {
            int improved = 0;
            long t0 = System.currentTimeMillis();

            for (int i = 0; i < params.length; i++) {
                int original = params[i];

                params[i] = original + step;
                Tunable.load(params);
                double up = error(samples, k);

                if (up < best) {
                    best = up;
                    improved++;
                    continue;
                }

                params[i] = original - step;
                Tunable.load(params);
                double down = error(samples, k);

                if (down < best) {
                    best = down;
                    improved++;
                } else {
                    params[i] = original;
                    Tunable.load(params);
                }
            }

            long secs = (System.currentTimeMillis() - t0) / 1000;
            System.out.printf("pass %d  step %d  improved %d/%d  error %.8f  (%ds)%n",
                    pass, step, improved, params.length, best, secs);

            if (improved == 0) {
                if (step == 1) { System.out.println("converged"); break; }
                step = Math.max(1, step / 2);
                System.out.println("  no improvement, halving step to " + step);
            }
        }

        Tunable.load(params);
        Tunable.write(out, params);
        Path raw = Path.of(out.toString().replaceAll("\\.txt$", "") + ".raw");
        Tunable.writeRaw(raw, params);
        System.out.println("wrote " + raw + " (loadable by the engine at runtime)");
        System.out.printf("%nfinal error: %.8f%n", best);
        System.out.println("wrote " + out);
        System.out.println("\nNOT VALIDATED. A lower error is not a stronger engine.");
        System.out.println("Run the SPRT harness against the untuned build before believing it.");
    }

    /** Sigmoid squashing a centipawn score into a predicted game result. */
    private static double sigmoid(double cp, double k) {
        return 1.0 / (1.0 + Math.pow(10.0, -k * cp / 400.0));
    }

    private static double error(List<Sample> samples, double k) {
        Psqt eval = new Psqt();
        double sum = 0;
        for (Sample s : samples) {
            // Evaluation is side-to-move relative; the label is White's result.
            int cp = eval.evaluate(s.board());
            if (s.board().sideToMove == Piece.BLACK) cp = -cp;
            double d = s.result() - sigmoid(cp, k);
            sum += d * d;
        }
        return sum / samples.size();
    }

    /** Ternary search for the K that best explains the data under the current eval. */
    private static double fitK(List<Sample> samples) {
        double lo = 0.2, hi = 3.0;
        for (int i = 0; i < 40; i++) {
            double m1 = lo + (hi - lo) / 3;
            double m2 = hi - (hi - lo) / 3;
            if (error(samples, m1) < error(samples, m2)) hi = m2; else lo = m1;
        }
        return (lo + hi) / 2;
    }

    private static List<Sample> load(Path path) throws IOException {
        List<Sample> out = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int bar = line.lastIndexOf('|');
            if (bar < 0) continue;
            try {
                Board b = Fen.parse(line.substring(0, bar).trim());
                double r = Double.parseDouble(line.substring(bar + 1).trim());
                out.add(new Sample(b, r));
            } catch (Exception ignored) { }
        }
        return out;
    }
}
