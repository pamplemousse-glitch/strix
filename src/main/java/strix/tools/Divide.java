package strix.tools;

import strix.core.Fen;
import strix.core.Perft;

/**
 * Per-root-move node counts, in the same format Stockfish's `go perft N` emits,
 * so the two can be diffed directly. This is the step-3-through-6 debugging tool.
 *
 *   java -cp build/classes/java/main strix.tools.Divide "&lt;fen&gt;" &lt;depth&gt;
 */
public final class Divide {
    public static void main(String[] args) {
        String fen = args.length > 0 ? args[0] : Fen.START;
        int depth = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        System.out.print(Perft.divide(Fen.parse(fen), depth));
    }
}
