package strix.core;

/**
 * Pseudo-legal generation followed by a legality filter.
 *
 * Fully-legal generation (computing pins and check evasions up front) is faster
 * but much harder to prove correct. Generate everything, then discard moves that
 * leave our own king attacked. See docs/adr/0003.
 *
 * Castling is the exception: "cannot castle through check" has to be checked
 * during generation, because after the move the king is already on its final
 * square and the filter would only catch "into check".
 */
public final class MoveGen {

    public static final int MAX_MOVES = 256;

    private MoveGen() {}

    public static int generateLegal(Board b, int[] out, int[] scratch) {
        int n = generatePseudoLegal(b, scratch);
        int us = b.sideToMove;
        int them = Piece.other(us);
        int count = 0;
        for (int i = 0; i < n; i++) {
            int move = scratch[i];
            b.make(move);
            if (!b.isAttacked(b.kingSquare(us), them)) out[count++] = move;
            b.unmake(move);
        }
        return count;
    }

    /**
     * Legal captures and promotions only, for quiescence search. Filters the full
     * generation rather than generating captures directly, which is slower but
     * cannot disagree with generateLegal about what is legal.
     */
    public static int generateCaptures(Board b, int[] out, int[] scratch) {
        int n = generateLegal(b, scratch, out);
        int count = 0;
        for (int i = 0; i < n; i++) {
            int m = scratch[i];
            if (Move.isCapture(m) || Move.isPromotion(m)) out[count++] = m;
        }
        return count;
    }

    public static int generatePseudoLegal(Board b, int[] out) {
        int n = 0;
        int us = b.sideToMove;
        int them = Piece.other(us);
        long ours = b.byColor[us];
        long theirs = b.byColor[them];

        n = pawns(b, us, them, theirs, out, n);
        n = leapers(b, Piece.KNIGHT, ours, theirs, out, n);
        n = leapers(b, Piece.KING, ours, theirs, out, n);
        n = sliders(b, us, Piece.BISHOP, ours, theirs, out, n);
        n = sliders(b, us, Piece.ROOK, ours, theirs, out, n);
        n = sliders(b, us, Piece.QUEEN, ours, theirs, out, n);
        n = castles(b, us, them, out, n);
        return n;
    }

    private static int pawns(Board b, int us, int them, long theirs, int[] out, int n) {
        long pawns = b.pieces(us, Piece.PAWN);
        int forward = (us == Piece.WHITE) ? 8 : -8;
        int startRank = (us == Piece.WHITE) ? 1 : 6;
        int promoRank = (us == Piece.WHITE) ? 7 : 0;

        while (pawns != 0L) {
            int from = Long.numberOfTrailingZeros(pawns);
            pawns &= pawns - 1;

            int to = from + forward;
            if (to >= 0 && to < 64 && b.mailbox[to] == Piece.NONE) {
                if (Square.rank(to) == promoRank) {
                    n = promotions(from, to, false, out, n);
                } else {
                    out[n++] = Move.of(from, to, Move.QUIET);
                    if (Square.rank(from) == startRank) {
                        int dbl = to + forward;
                        if (b.mailbox[dbl] == Piece.NONE) {
                            out[n++] = Move.of(from, dbl, Move.DOUBLE_PUSH);
                        }
                    }
                }
            }

            long caps = Attacks.PAWN[us][from] & theirs;
            while (caps != 0L) {
                int c = Long.numberOfTrailingZeros(caps);
                caps &= caps - 1;
                if (Square.rank(c) == promoRank) {
                    n = promotions(from, c, true, out, n);
                } else {
                    out[n++] = Move.of(from, c, Move.CAPTURE);
                }
            }

            if (b.epSquare != Square.NONE
                    && (Attacks.PAWN[us][from] & (1L << b.epSquare)) != 0L) {
                out[n++] = Move.of(from, b.epSquare, Move.EP_CAPTURE);
            }
        }
        return n;
    }

    private static int promotions(int from, int to, boolean capture, int[] out, int n) {
        int base = capture ? Move.PROMO_N_CAPTURE : Move.PROMO_N;
        for (int i = 0; i < 4; i++) out[n++] = Move.of(from, to, base + i);
        return n;
    }

    private static int leapers(Board b, int type, long ours, long theirs, int[] out, int n) {
        long from = b.pieces(b.sideToMove, type);
        while (from != 0L) {
            int sq = Long.numberOfTrailingZeros(from);
            from &= from - 1;
            long targets = (type == Piece.KNIGHT ? Attacks.KNIGHT[sq] : Attacks.KING[sq]) & ~ours;
            n = emit(sq, targets, theirs, out, n);
        }
        return n;
    }

    private static int sliders(Board b, int us, int type, long ours, long theirs, int[] out, int n) {
        long from = b.pieces(us, type);
        while (from != 0L) {
            int sq = Long.numberOfTrailingZeros(from);
            from &= from - 1;
            long attacks = switch (type) {
                case Piece.BISHOP -> Magic.bishop(sq, b.occupied);
                case Piece.ROOK   -> Magic.rook(sq, b.occupied);
                default           -> Magic.queen(sq, b.occupied);
            };
            n = emit(sq, attacks & ~ours, theirs, out, n);
        }
        return n;
    }

    private static int emit(int from, long targets, long theirs, int[] out, int n) {
        while (targets != 0L) {
            int to = Long.numberOfTrailingZeros(targets);
            targets &= targets - 1;
            out[n++] = Move.of(from, to, ((theirs >>> to) & 1L) != 0L ? Move.CAPTURE : Move.QUIET);
        }
        return n;
    }

    private static int castles(Board b, int us, int them, int[] out, int n) {
        int base = (us == Piece.WHITE) ? 0 : 56;
        int kingRight = (us == Piece.WHITE) ? Board.CASTLE_WK : Board.CASTLE_BK;
        int queenRight = (us == Piece.WHITE) ? Board.CASTLE_WQ : Board.CASTLE_BQ;
        int e = base + 4, f = base + 5, g = base + 6, d = base + 3, c = base + 2, bSq = base + 1;

        if (b.isAttacked(e, them)) return n;   // cannot castle out of check

        if ((b.castling & kingRight) != 0
                && b.mailbox[f] == Piece.NONE && b.mailbox[g] == Piece.NONE
                && !b.isAttacked(f, them) && !b.isAttacked(g, them)) {
            out[n++] = Move.of(e, g, Move.CASTLE_KING);
        }
        if ((b.castling & queenRight) != 0
                && b.mailbox[d] == Piece.NONE && b.mailbox[c] == Piece.NONE
                && b.mailbox[bSq] == Piece.NONE
                && !b.isAttacked(d, them) && !b.isAttacked(c, them)) {
            out[n++] = Move.of(e, c, Move.CASTLE_QUEEN);
        }
        return n;
    }
}
