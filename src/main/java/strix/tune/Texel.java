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
 * <h2>Why there is a validation split</h2>
 * The first full run dropped training error by 5% and produced a knight table that
 * was visibly noise: +96 on one square beside -62 on its neighbour, and a pawn on
 * g7 scored at -14. A lower error is not a better evaluation.
 *
 * The cause is that positions inside one game are not independent observations.
 * 198,014 positions came from 6,000 games, so roughly 33 positions share each
 * result label, the same pawn structure and the same pieces. The effective sample
 * size is nearer 6,000 than 198,000, which is about 13 independent samples per
 * parameter, and coordinate descent will happily spend a dozen passes fitting
 * individual squares to the quirks of individual games.
 *
 * So a fifth of the games are held out and never tuned on. Training error always
 * falls; validation error falls while the tuner is learning chess and rises the
 * moment it starts memorising the dataset. Tuning stops at that turn.
 *
 * The split is by GAME, not by position. Splitting by position would scatter
 * positions from the same game across both sets, so the validation set would
 * contain near-duplicates of training data and would report no overfitting at all.
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

        List<List<Sample>> games = loadByGame(data);
        int totalPositions = games.stream().mapToInt(List::size).sum();

        // Split by GAME. Splitting by position would put near-duplicates of the
        // training data into the validation set and hide overfitting entirely.
        // Shuffled, like Trainer does, and with the same fixed seed so a run is
        // still reproducible. Taking the first fifth in file order made the
        // validation set the OLDEST games; on a file that concatenates a legacy
        // run with a current one, loadByGame appends the id-tagged games after
        // the heuristic ones, so the entire holdout came from the legacy part.
        java.util.Collections.shuffle(games, new java.util.Random(12345));

        // Two games is the minimum that can be split at all. Below that `train`
        // comes out empty, error() returns 0/0 = NaN, every `improved < best`
        // comparison against NaN is false so no parameter ever moves, and the
        // tool writes the UNTUNED values while printing "wrote ...". A silent
        // no-op that looks like a completed run.
        if (games.size() < 2) {
            System.err.printf("only %d game(s) in %s: nothing to hold out, refusing.%n",
                    games.size(), data);
            System.err.println("A legacy file whose results are all identical collapses to");
            System.err.println("one game under the label-run heuristic. See Dataset.");
            System.exit(2);
        }

        int holdout = Math.max(1, games.size() / 5);
        List<Sample> validate = new ArrayList<>();
        List<Sample> train = new ArrayList<>();
        for (int i = 0; i < games.size(); i++) {
            (i < holdout ? validate : train).addAll(games.get(i));
        }

        System.out.printf("loaded %,d positions from %,d games%n", totalPositions, games.size());
        System.out.printf("train %,d positions (%,d games)   validate %,d (%,d games)%n",
                train.size(), games.size() - holdout, validate.size(), holdout);

        int[] params = Tunable.export();
        System.out.printf("tuning %d parameters%n%n", params.length);

        double k = fitK(train);
        System.out.printf("K = %.4f  (fitted on train, against the current eval)%n", k);

        double best = error(train, k);
        double bestValidation = error(validate, k);
        int[] bestParams = params.clone();
        System.out.printf("start:  train %.8f   validate %.8f%n%n", best, bestValidation);

        int step = 8;
        for (int pass = 1; pass <= maxPasses; pass++) {
            int improved = 0;
            long t0 = System.currentTimeMillis();

            for (int i = 0; i < params.length; i++) {
                int original = params[i];

                params[i] = original + step;
                Tunable.load(params);
                double up = error(train, k);

                if (up < best) {
                    best = up;
                    improved++;
                    continue;
                }

                params[i] = original - step;
                Tunable.load(params);
                double down = error(train, k);

                if (down < best) {
                    best = down;
                    improved++;
                } else {
                    params[i] = original;
                    Tunable.load(params);
                }
            }

            double validation = error(validate, k);
            long secs = (System.currentTimeMillis() - t0) / 1000;
            boolean better = validation < bestValidation;
            System.out.printf("pass %2d  step %d  improved %3d/%d  train %.8f  validate %.8f %s (%ds)%n",
                    pass, step, improved, params.length, best, validation,
                    better ? "" : "  <- worse", secs);

            if (better) {
                bestValidation = validation;
                bestParams = params.clone();
            } else {
                // Held-out error turned around. Everything past this point is the
                // tuner memorising the training games rather than learning chess.
                System.out.println("\nvalidation error stopped improving: stopping here");
                break;
            }

            if (improved == 0) {
                if (step == 1) { System.out.println("converged"); break; }
                step = Math.max(1, step / 2);
                System.out.println("  no improvement, halving step to " + step);
            }
        }

        params = bestParams;
        Tunable.load(params);
        Tunable.write(out, params);
        Path raw = Path.of(out.toString().replaceAll("\\.txt$", "") + ".raw");
        Tunable.writeRaw(raw, params);
        System.out.println("wrote " + raw + " (loadable by the engine at runtime)");
        System.out.printf("%nbest validation error: %.8f%n", bestValidation);
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

    /**
     * Group positions by the game they came from.
     *
     * Files written by the current {@link SelfPlay} carry an explicit game id and
     * are grouped by it exactly. See {@link Dataset}.
     *
     * Older files do not, and fall back to the original heuristic: the generator
     * writes a game's positions consecutively and they share a result label, so a
     * run of identical labels is a good enough boundary. It under-counts, because
     * two consecutive games with the same result merge into one, which is
     * conservative in the right direction: it can only make the split MORE
     * separated, never less.
     */
    private static List<List<Sample>> loadByGame(Path path) throws IOException {
        java.util.Map<Integer, List<Sample>> byId = new java.util.LinkedHashMap<>();
        List<List<Sample>> games = new ArrayList<>();
        List<Sample> current = new ArrayList<>();
        Double lastLabel = null;

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Dataset.Row row = Dataset.parse(line);
            if (row == null) continue;
            try {
                Board b = Fen.parse(row.fen());
                double r = Double.parseDouble(row.label());

                if (row.gameId() != Dataset.NO_GAME) {
                    byId.computeIfAbsent(row.gameId(), k -> new ArrayList<>())
                        .add(new Sample(b, r));
                    continue;
                }

                // Legacy file with no game id: fall back to the label-run
                // heuristic below.
                if (lastLabel != null && r != lastLabel && !current.isEmpty()) {
                    games.add(current);
                    current = new ArrayList<>();
                }
                lastLabel = r;
                current.add(new Sample(b, r));
            } catch (Exception ignored) { }
        }
        if (!current.isEmpty()) games.add(current);
        games.addAll(byId.values());
        return games;
    }
}
