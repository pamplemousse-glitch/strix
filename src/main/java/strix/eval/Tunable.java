package strix.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Flattens every evaluation parameter into one int array and back.
 *
 * The tuner does not know or care what any individual number means. It nudges
 * index 137, sees whether the error went down, and keeps or reverts. Having a
 * single flat view is what makes that possible without the tuner needing to
 * understand chess.
 *
 * Ordering is fixed and must not change, or a previously tuned file will load
 * its values into the wrong squares.
 */
public final class Tunable {

    private static final int[][] TABLES = {
            Psqt.PAWN, Psqt.KNIGHT, Psqt.BISHOP, Psqt.ROOK, Psqt.QUEEN,
            Psqt.KING_MG, Psqt.KING_EG
    };
    private static final String[] NAMES = {
            "PAWN", "KNIGHT", "BISHOP", "ROOK", "QUEEN", "KING_MG", "KING_EG"
    };

    /** Piece values for pawn through queen. The king's is not a number, it is infinity. */
    private static final int MATERIAL_COUNT = 5;

    private Tunable() {}

    public static int count() {
        return TABLES.length * 64 + MATERIAL_COUNT;
    }

    public static int[] export() {
        int[] out = new int[count()];
        int i = 0;
        for (int[] table : TABLES) {
            System.arraycopy(table, 0, out, i, 64);
            i += 64;
        }
        System.arraycopy(Material.VALUE, 0, out, i, MATERIAL_COUNT);
        return out;
    }

    public static void load(int[] params) {
        int i = 0;
        for (int[] table : TABLES) {
            System.arraycopy(params, i, table, 0, 64);
            i += 64;
        }
        System.arraycopy(params, i, Material.VALUE, 0, MATERIAL_COUNT);
    }

    /**
     * A plain list of integers, one per line, for loading at runtime.
     *
     * This exists so the SPRT harness can run a tuned build against an untuned one
     * without recompiling either. The Java output below is for the eventual
     * paste-in once the values have actually been proven better.
     */
    public static void writeRaw(Path path, int[] params) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int p : params) sb.append(p).append('\n');
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    public static void loadRaw(Path path) throws IOException {
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int[] params = new int[count()];
        int i = 0;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (i >= params.length) break;
            params[i++] = Integer.parseInt(line);
        }
        if (i != params.length) {
            throw new IOException("expected " + params.length + " values, found " + i);
        }
        load(params);
    }

    /**
     * Emit the tuned values as pasteable Java, rather than a binary blob.
     *
     * Tuned tables are readable: a knight table should still show a bonus in the
     * centre and a penalty on the rim. If it does not, something went wrong, and
     * you only notice that if the output is something a person can look at.
     */
    public static void write(Path path, int[] params) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// Texel-tuned. Generated, do not edit by hand.\n\n");
        int i = 0;
        for (int t = 0; t < TABLES.length; t++) {
            sb.append("    static final int[] ").append(NAMES[t]).append(" = {\n");
            for (int rank = 0; rank < 8; rank++) {
                sb.append("       ");
                for (int file = 0; file < 8; file++) {
                    sb.append(String.format("%5d", params[i + rank * 8 + file]));
                    if (!(rank == 7 && file == 7)) sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("    };\n\n");
            i += 64;
        }
        sb.append("    // pawn, knight, bishop, rook, queen\n");
        sb.append("    public static final int[] VALUE = {");
        for (int m = 0; m < MATERIAL_COUNT; m++) {
            sb.append(params[i + m]).append(m < MATERIAL_COUNT - 1 ? ", " : "");
        }
        sb.append(", 0};\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}
