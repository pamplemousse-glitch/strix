package strix.search;

import strix.core.*;
import strix.eval.Material;

/**
 * Static exchange evaluation: what a capture is worth if both sides keep
 * capturing on that square with their cheapest attacker.
 *
 * <h2>What it answers that MVV-LVA cannot</h2>
 * MVV-LVA sorts by "how big is the victim, how small is the attacker", which is
 * a guess about the first move only. It cannot see that RxP is losing when the
 * pawn is defended, so the engine tries the rook capture first, searches the
 * refutation, and throws the work away. SEE plays the whole exchange out.
 *
 * <h2>Why this is worth building here specifically</h2>
 * ADR 0018 found that the search techniques which worked in this engine bet on
 * MOVE ORDERING and the ones that failed bet on the EVALUATION. SEE is squarely
 * in the first group: it reads material off the board and never asks the
 * evaluation function anything. So the ADR predicts it should gain, and this is
 * the measurement that tests that prediction rather than assuming it.
 */
public final class See {

    private See() {}

    /**
     * Piece values for the exchange only.
     *
     * The king is huge rather than zero. It can capture, so it belongs in the
     * attacker order, but a king capture into a square the opponent still
     * attacks is illegal; a value that dwarfs everything makes the swap-off
     * arithmetic refuse it on its own instead of needing a special case.
     */
    private static final int[] VALUE = {
        Material.VALUE[Piece.PAWN], Material.VALUE[Piece.KNIGHT],
        Material.VALUE[Piece.BISHOP], Material.VALUE[Piece.ROOK],
        Material.VALUE[Piece.QUEEN], 10_000
    };

    /** Every piece of either colour attacking {@code sq}, given an occupancy. */
    private static long attackersTo(Board b, int sq, long occupied) {
        long attackers = 0L;
        attackers |= Attacks.PAWN[Piece.BLACK][sq] & b.pieces(Piece.WHITE, Piece.PAWN);
        attackers |= Attacks.PAWN[Piece.WHITE][sq] & b.pieces(Piece.BLACK, Piece.PAWN);
        attackers |= Attacks.KNIGHT[sq]
                & (b.pieces(Piece.WHITE, Piece.KNIGHT) | b.pieces(Piece.BLACK, Piece.KNIGHT));
        attackers |= Attacks.KING[sq]
                & (b.pieces(Piece.WHITE, Piece.KING) | b.pieces(Piece.BLACK, Piece.KING));

        long bishopLike = b.pieces(Piece.WHITE, Piece.BISHOP) | b.pieces(Piece.BLACK, Piece.BISHOP)
                | b.pieces(Piece.WHITE, Piece.QUEEN) | b.pieces(Piece.BLACK, Piece.QUEEN);
        long rookLike = b.pieces(Piece.WHITE, Piece.ROOK) | b.pieces(Piece.BLACK, Piece.ROOK)
                | b.pieces(Piece.WHITE, Piece.QUEEN) | b.pieces(Piece.BLACK, Piece.QUEEN);

        // Recomputed against the CURRENT occupancy on every call, which is what
        // uncovers x-rays: a rook behind a rook joins the exchange the moment
        // the piece in front of it captures and leaves the square.
        attackers |= Magic.bishop(sq, occupied) & bishopLike;
        attackers |= Magic.rook(sq, occupied) & rookLike;
        return attackers & occupied;
    }

    /** The cheapest attacker of {@code color} in {@code attackers}, or -1. */
    private static int leastValuable(Board b, long attackers, int color, int[] typeOut) {
        for (int type = Piece.PAWN; type <= Piece.KING; type++) {
            long subset = attackers & b.pieces(color, type);
            if (subset != 0L) {
                typeOut[0] = type;
                return Long.numberOfTrailingZeros(subset);
            }
        }
        return -1;
    }

    /**
     * Net material gain, in centipawns, for the side making {@code move}.
     *
     * Positive is winning material, zero is an even trade, negative is losing.
     */
    public static int evaluate(Board b, int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int flag = Move.flag(move);

        int movingPiece = b.mailbox[from];
        if (movingPiece == Piece.NONE) return 0;
        int movingType = Piece.typeOf(movingPiece);
        int us = Piece.colorOf(movingPiece);

        long occupied = b.occupied;
        int captured;

        if (flag == Move.EP_CAPTURE) {
            // The captured pawn is not on the destination square, so it has to
            // be cleared separately or the exchange is computed against a board
            // where it is still defending.
            int capturedSq = (us == Piece.WHITE) ? to - 8 : to + 8;
            occupied &= ~(1L << capturedSq);
            captured = Piece.PAWN;
        } else {
            int victim = b.mailbox[to];
            captured = (victim == Piece.NONE) ? -1 : Piece.typeOf(victim);
        }

        int[] gain = new int[32];
        gain[0] = (captured < 0) ? 0 : VALUE[captured];

        // A promotion changes what is standing on the square afterwards, so both
        // the immediate gain and the piece that can be recaptured change.
        int onSquare = movingType;
        if (Move.isPromotion(move)) {
            int promo = Move.promoPiece(move);
            gain[0] += VALUE[promo] - VALUE[Piece.PAWN];
            onSquare = promo;
        }

        occupied &= ~(1L << from);
        long attackers = attackersTo(b, to, occupied);

        int side = Piece.other(us);
        int depth = 0;
        int[] typeOut = new int[1];

        // The counter advances once per capture INCLUDING the original move,
        // and the gain is computed before the next attacker is looked for.
        // Doing it the other way round leaves the fold-back one step short, so
        // a single recapture is never folded in at all and every defended
        // capture reads as free.
        while (true) {
            depth++;
            if (depth >= gain.length - 1) break;

            // Whatever is standing on the square can be taken, and taking it
            // wins that piece minus whatever the opponent stood to win.
            gain[depth] = VALUE[onSquare] - gain[depth - 1];

            long sideAttackers = attackers & colorMask(b, side);
            if (sideAttackers == 0L) break;

            int sq = leastValuable(b, sideAttackers, side, typeOut);
            if (sq < 0) break;

            onSquare = typeOut[0];
            occupied &= ~(1L << sq);
            // Recomputed against the new occupancy, which is what lets a piece
            // behind the one that just captured join the exchange.
            attackers = attackersTo(b, to, occupied);
            side = Piece.other(side);
        }

        // Fold back: at every point a side may decline, so each gain becomes
        // the better of capturing and standing pat.
        //
        // No early exit here. The conventional "both options are losing, so
        // prune" test misfires with this indexing: on doubled rooks against a
        // defended pawn it cut before the second rook recaptured and returned
        // -400 where the exchange is -300. An exchange is a handful of
        // iterations, so the saving was not worth the wrong answer.
        while (--depth > 0) {
            gain[depth - 1] = -Math.max(-gain[depth - 1], gain[depth]);
        }
        return gain[0];
    }

    private static long colorMask(Board b, int color) {
        long mask = 0L;
        for (int type = Piece.PAWN; type <= Piece.KING; type++) mask |= b.pieces(color, type);
        return mask;
    }
}
