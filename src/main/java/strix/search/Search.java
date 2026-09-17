package strix.search;

import strix.core.Board;
import strix.core.Move;
import strix.core.MoveGen;
import strix.eval.Evaluator;

/**
 * Two searches that must always agree.
 *
 * {@link #negamax} is plain minimax with no pruning. It is slow, it will never be
 * used to play a game, and it is kept forever on purpose: it is the reference
 * answer that {@link #alphaBeta} has to reproduce.
 *
 * Alpha-beta is an OPTIMIZATION, not an improvement. It must return the identical
 * score having examined fewer nodes. If the scores ever differ, alpha-beta has a
 * bug, full stop. That is what SearchTest asserts.
 */
public final class Search {

    /** Iterations the best move must survive unchanged before we stop early. */
    private static final int STABLE_ENOUGH = 4;

    public static final int MATE = 30_000;
    public static final int INFINITY = 31_000;
    private static final int MAX_PLY = 64;

    private Evaluator evaluator;
    private final int[][] moveBuf = new int[MAX_PLY][MoveGen.MAX_MOVES];
    private final int[][] scratch = new int[MAX_PLY][MoveGen.MAX_MOVES];

    public long nodes;
    public int bestMove;

    /** Both off for the negamax-vs-alphabeta invariant test, on for real play. */
    public boolean useQuiescence = true;
    public TranspositionTable tt;
    public Ordering ordering;

    private volatile boolean stopped;
    private long deadline = Long.MAX_VALUE;
    private long nodeLimit = Long.MAX_VALUE;
    private long nodesThisMove;
    private Listener listener = (d, sc, n, ms, mv) -> {};

    /** Receives one call per completed iteration, for UCI info lines. */
    public interface Listener {
        void onDepth(int depth, int score, long nodes, long millis, int bestMove);
    }

    public Search(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setEvaluator(Evaluator e) { this.evaluator = e; }

    /** Callable from another thread. */
    public void stop() { stopped = true; }

    /**
     * Iterative deepening: search depth 1, then 2, then 3, until time runs out.
     *
     * This looks wasteful and is not. A move is always ready if the clock dies
     * mid-iteration, and because the tree grows exponentially every shallower
     * search together costs a fraction of the deepest one.
     *
     * An aborted iteration's result is DISCARDED. A partially searched depth can
     * be arbitrarily worse than the completed depth below it, so the move played
     * is always the last fully completed one.
     */
    public int think(Board board, SearchLimits limits) {
        stopped = false;
        long start = System.currentTimeMillis();
        long budget = limits.allocate(board.sideToMove);
        deadline = (budget == Long.MAX_VALUE) ? Long.MAX_VALUE : start + budget;

        long nodeBudget = limits.allocateNodes(board.sideToMove);
        nodeLimit = (nodeBudget <= 0) ? Long.MAX_VALUE : nodeBudget;
        nodesThisMove = 0;
        boolean byNodes = nodeLimit != Long.MAX_VALUE;

        if (ordering != null) ordering.clear();

        // One legal move is not a decision. Play it and keep the clock.
        int[] rootMoves = moveBuf[0];
        int rootCount = MoveGen.generateLegal(board, rootMoves, scratch[0]);
        if (rootCount == 1) {
            bestMove = rootMoves[0];
            listener.onDepth(1, 0, 1, 0, bestMove);
            return 0;
        }

        int completedMove = Move.NONE;
        int completedScore = 0;
        int stableFor = 0;

        for (int depth = 1; depth <= limits.depth && depth < MAX_PLY; depth++) {
            nodes = 0;
            bestMove = Move.NONE;
            int score = alphaBeta(board, depth, -INFINITY, INFINITY, 0, true);

            if (stopped) break;                      // discard the partial result

            stableFor = (bestMove == completedMove) ? stableFor + 1 : 0;
            completedMove = bestMove;
            completedScore = score;
            listener.onDepth(depth, score, nodes, System.currentTimeMillis() - start, completedMove);

            // The answer stopped changing several iterations ago. Searching deeper
            // is unlikely to change it, and the clock is worth more elsewhere. This
            // is what makes an obvious recapture instant instead of a full think.
            if (stableFor >= STABLE_ENOUGH && depth >= 6) break;

            // No point starting an iteration we cannot possibly finish. The next
            // one costs several times this one, so half the budget is the cutoff.
            // Soft limit: do not START an iteration that cannot finish. The next
            // one costs several times this one, so a third of the budget is the
            // point of no return. The hard limit still aborts mid-iteration.
            if (byNodes) {
                if (nodesThisMove > nodeLimit / 3) break;
            } else if (System.currentTimeMillis() - start > budget / 3) {
                break;
            }
            if (Math.abs(score) > MATE - MAX_PLY) break;   // forced mate found
        }

        bestMove = completedMove;
        return completedScore;
    }

    /** Plain minimax. No pruning. The reference implementation. */
    public int negamax(Board board, int depth) {
        nodes = 0;
        bestMove = Move.NONE;
        return negamax(board, depth, 0, true);
    }

    private int negamax(Board board, int depth, int ply, boolean root) {
        nodes++;
        int[] moves = moveBuf[ply];
        int n = MoveGen.generateLegal(board, moves, scratch[ply]);

        if (n == 0) return board.inCheck(board.sideToMove) ? -MATE + ply : 0;
        if (depth == 0) return evaluator.evaluate(board);

        int best = -INFINITY;
        for (int i = 0; i < n; i++) {
            board.make(moves[i]);
            int score = -negamax(board, depth - 1, ply + 1, false);
            board.unmake(moves[i]);

            if (score > best) {
                best = score;
                if (root) bestMove = moves[i];
            }
        }
        return best;
    }

    /**
     * Quiescence search: at the leaves, keep going until the position is quiet.
     *
     * Without this, a fixed-depth search stops in the middle of a capture
     * sequence and evaluates a position where a piece is hanging, concluding it
     * is a pawn up when the recapture is one ply past the horizon. That is the
     * horizon effect and it makes the engine hang pieces constantly.
     *
     * "Stand pat" is the key idea: you are not obliged to capture, so the static
     * evaluation is a floor. If it already beats beta, stop.
     *
     * Unlike alpha-beta, this is NOT answer-preserving. It deliberately changes
     * the result, which is why it cannot be verified by comparison against
     * negamax and needs its own tests.
     */
    private int quiescence(Board board, int alpha, int beta, int ply) {
        nodesThisMove++;
        if ((++nodes & 2047L) == 0L) {
            if (nodesThisMove >= nodeLimit) stopped = true;
            else if (System.currentTimeMillis() >= deadline) stopped = true;
        }
        if (stopped) return 0;
        if (ply >= MAX_PLY - 1) return evaluator.evaluate(board);

        int standPat = evaluator.evaluate(board);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        int[] moves = moveBuf[ply];
        int n = MoveGen.generateCaptures(board, moves, scratch[ply]);
        for (int i = 0; i < n; i++) {
            board.make(moves[i]);
            int score = -quiescence(board, -beta, -alpha, ply + 1);
            board.unmake(moves[i]);
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    /** Alpha-beta. Same answer as negamax, fewer nodes. */
    public int alphaBeta(Board board, int depth) {
        nodes = 0;
        bestMove = Move.NONE;
        return alphaBeta(board, depth, -INFINITY, INFINITY, 0, true);
    }

    private int alphaBeta(Board board, int depth, int alpha, int beta, int ply, boolean root) {
        // Checking the clock costs a syscall, so do it every 2048 nodes rather
        // than every node.
        nodesThisMove++;
        if ((++nodes & 2047L) == 0L) {
            if (nodesThisMove >= nodeLimit) stopped = true;
            else if (System.currentTimeMillis() >= deadline) stopped = true;
        }
        if (stopped && !root) return 0;

        // A draw is a draw regardless of how good the position looks. Without
        // this the engine will happily repeat while winning, and will not steer
        // toward repetition when losing.
        //
        // Two occurrences, not three: inside a search, a single repetition already
        // means the side to move can force the draw, so treating it as one avoids
        // wasting depth proving it twice.
        if (!root && (board.isRepetition(2) || board.isFiftyMoveDraw()
                || board.isInsufficientMaterial())) {
            return 0;
        }

        int alphaOriginal = alpha;
        int ttMove = Move.NONE;
        if (tt != null) {
            if (!root) {
                int hit = tt.probe(board.hash, depth, alpha, beta, ply);
                if (hit != TranspositionTable.MISS) return hit;
            }
            ttMove = tt.probeMove(board.hash);
        }

        int[] moves = moveBuf[ply];
        int n = MoveGen.generateLegal(board, moves, scratch[ply]);

        if (n == 0) return board.inCheck(board.sideToMove) ? -MATE + ply : 0;
        if (depth == 0) {
            return useQuiescence ? quiescence(board, alpha, beta, ply) : evaluator.evaluate(board);
        }

        int bestScore = -INFINITY;
        int bestLocal = Move.NONE;

        for (int i = 0; i < n; i++) {
            if (ordering != null) ordering.pickBest(board, moves, n, i, ttMove, ply);
            int move = moves[i];

            board.make(move);
            int score = -alphaBeta(board, depth - 1, -beta, -alpha, ply + 1, false);
            board.unmake(move);

            if (score > bestScore) {
                bestScore = score;
                bestLocal = move;
            }
            if (score > alpha) {
                alpha = score;
                if (root) bestMove = move;
            }
            // The opponent already has a reply at least this good, so they will
            // never let us reach this position. Stop looking at our other moves.
            if (alpha >= beta) {
                if (ordering != null) ordering.onCutoff(board, move, depth, ply);
                break;
            }
        }

        if (tt != null && !stopped) {
            int flag = (bestScore <= alphaOriginal) ? TranspositionTable.UPPER
                     : (bestScore >= beta) ? TranspositionTable.LOWER
                     : TranspositionTable.EXACT;
            tt.store(board.hash, depth, bestScore, flag, bestLocal, ply);
        }
        return bestScore;
    }
}
