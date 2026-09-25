package strix.tune;

/**
 * The one place that knows the dataset line format, so the three tools that read
 * it cannot drift apart.
 *
 * <pre>    FEN | label | gameId</pre>
 *
 * {@code gameId} is optional and absent from files written before it existed.
 * A FEN never contains a pipe, so splitting on it is unambiguous.
 *
 * <h2>Why the game id is here at all</h2>
 * Positions from one game are not independent samples. They share a pawn
 * structure, a set of pieces and, in the Texel dataset, a result label. A
 * holdout split that scatters them across both sides puts near-duplicates of the
 * training data into the validation set, which then reports no overfitting no
 * matter how much there is.
 *
 * {@link Texel} avoided this by treating a run of identical result labels as one
 * game, which works only because there are three possible labels and they repeat.
 * {@link Trainer} reads Stockfish evaluations, which are near-unique per
 * position, so there is no run to detect and that trick does not transfer. Worse,
 * {@link Label} writes from several workers at once, so a game's positions are
 * not even contiguous by the time the trainer sees them.
 *
 * Hence an explicit id, carried end to end.
 */
final class Dataset {

    /** Used when a line carries no game id, i.e. a file written before this format. */
    static final int NO_GAME = -1;

    private Dataset() {}

    record Row(String fen, String label, int gameId) {}

    /** Null for a line that is blank, a comment, or has no label. */
    static Row parse(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) return null;

        int first = s.indexOf('|');
        if (first < 0) return null;

        String fen = s.substring(0, first).trim();
        if (fen.isEmpty()) return null;

        String rest = s.substring(first + 1);
        int second = rest.indexOf('|');

        String label = (second < 0 ? rest : rest.substring(0, second)).trim();
        if (label.isEmpty()) return null;

        int gameId = NO_GAME;
        if (second >= 0) {
            try {
                gameId = Integer.parseInt(rest.substring(second + 1).trim());
            } catch (NumberFormatException e) {
                gameId = NO_GAME;
            }
        }
        return new Row(fen, label, gameId);
    }

    /** The canonical way to write a line, so writers agree with {@link #parse}. */
    static String format(String fen, String label, int gameId) {
        return gameId == NO_GAME
                ? fen + " | " + label
                : fen + " | " + label + " | " + gameId;
    }
}
