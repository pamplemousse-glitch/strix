package strix.core;

/**
 * Board state: twelve bitboards plus a piece-per-square mailbox kept in sync.
 * The mailbox exists so "what is on this square" is one array read instead of
 * scanning twelve bitboards, which matters on every capture.
 *
 * make/unmake mutates in place rather than copying. See docs/adr/0004.
 */
public final class Board {

    public final long[] bb = new long[12];
    public final int[] mailbox = new int[64];
    public final long[] byColor = new long[2];
    public long occupied;

    public int sideToMove;
    public int castling;        // WK=1 WQ=2 BK=4 BQ=8
    public int epSquare;        // Square.NONE when unavailable
    public int halfmoveClock;
    public int fullmove;

    /** Maintained incrementally. Must always equal Zobrist.compute(this). */
    public long hash;

    public static final int CASTLE_WK = 1, CASTLE_WQ = 2, CASTLE_BK = 4, CASTLE_BQ = 8;

    /**
     * Moving from or to a square can extinguish castling rights. Masking by both
     * the from-square and the to-square handles every case in one operation,
     * including the one everybody forgets: an enemy rook captured on its own
     * home square loses that side its rights.
     */
    private static final int[] CASTLE_MASK = new int[64];
    static {
        java.util.Arrays.fill(CASTLE_MASK, 0b1111);
        CASTLE_MASK[Square.E1] &= ~(CASTLE_WK | CASTLE_WQ);
        CASTLE_MASK[Square.H1] &= ~CASTLE_WK;
        CASTLE_MASK[Square.A1] &= ~CASTLE_WQ;
        CASTLE_MASK[Square.E8] &= ~(CASTLE_BK | CASTLE_BQ);
        CASTLE_MASK[Square.H8] &= ~CASTLE_BK;
        CASTLE_MASK[Square.A8] &= ~CASTLE_BQ;
    }

    /**
     * Grown on demand rather than fixed, because the fixed version threw.
     *
     * uci/Main replays the entire game from the root board before searching, so
     * the index is game plies plus up to MAX_PLY of search, not search alone.
     * 1024 sounds generous and is about 480 moves; a bot game that shuffles can
     * reach it, and the failure was an ArrayIndexOutOfBoundsException thrown out
     * of make() in the middle of a real game on a real clock.
     */
    private int[] undo = new int[1024];
    private long[] history = new long[1024];
    private int ply;

    private void ensureCapacity() {
        if (ply < undo.length) return;
        undo = java.util.Arrays.copyOf(undo, undo.length * 2);
        history = java.util.Arrays.copyOf(history, history.length * 2);
    }

    public Board() {
        java.util.Arrays.fill(mailbox, Piece.NONE);
        epSquare = Square.NONE;
        fullmove = 1;
    }

    public void put(int piece, int sq) {
        bb[piece] |= 1L << sq;
        byColor[Piece.colorOf(piece)] |= 1L << sq;
        occupied |= 1L << sq;
        mailbox[sq] = piece;
        hash ^= Zobrist.PIECE[piece][sq];
    }

    private void remove(int piece, int sq) {
        long mask = ~(1L << sq);
        bb[piece] &= mask;
        byColor[Piece.colorOf(piece)] &= mask;
        occupied &= mask;
        mailbox[sq] = Piece.NONE;
        hash ^= Zobrist.PIECE[piece][sq];
    }

    private void relocate(int piece, int from, int to) {
        long mask = (1L << from) | (1L << to);
        bb[piece] ^= mask;
        byColor[Piece.colorOf(piece)] ^= mask;
        occupied ^= mask;
        mailbox[from] = Piece.NONE;
        mailbox[to] = piece;
        hash ^= Zobrist.PIECE[piece][from] ^ Zobrist.PIECE[piece][to];
    }

    public long pieces(int color, int type) { return bb[Piece.index(color, type)]; }

    public int kingSquare(int color) {
        return Long.numberOfTrailingZeros(bb[Piece.index(color, Piece.KING)]);
    }

    /** Is {@code sq} attacked by any piece of {@code by}? */
    public boolean isAttacked(int sq, int by) {
        if ((Attacks.PAWN[Piece.other(by)][sq] & pieces(by, Piece.PAWN)) != 0L) return true;
        if ((Attacks.KNIGHT[sq] & pieces(by, Piece.KNIGHT)) != 0L) return true;
        if ((Attacks.KING[sq] & pieces(by, Piece.KING)) != 0L) return true;
        long bishopLike = pieces(by, Piece.BISHOP) | pieces(by, Piece.QUEEN);
        if ((Magic.bishop(sq, occupied) & bishopLike) != 0L) return true;
        long rookLike = pieces(by, Piece.ROOK) | pieces(by, Piece.QUEEN);
        return (Magic.rook(sq, occupied) & rookLike) != 0L;
    }

    public boolean inCheck(int color) {
        return isAttacked(kingSquare(color), Piece.other(color));
    }

    // Undo record packing: captured piece (4) | castling (4) | ep+1 (7) | clock (12)
    //
    // The clock field was 8 bits, which silently truncated any halfmove clock of
    // 256 or more: `position fen ... 300 200` then make/unmake restored 44. That
    // is a make/unmake asymmetry, and it feeds both isFiftyMoveDraw() and the
    // lookback bound in isRepetition(). 12 bits holds 4095, far past the 100 the
    // fifty-move rule can ever leave standing, and bits 27-31 are still spare.
    private static int packUndo(int captured, int castling, int ep, int clock) {
        int e = (ep == Square.NONE) ? 0 : ep + 1;
        return (captured & 0xF) | ((castling & 0xF) << 4) | ((e & 0x7F) << 8)
                | ((Math.min(clock, 0xFFF) & 0xFFF) << 15);
    }
    private static int undoCaptured(int u) { int c = u & 0xF; return c == 0xF ? Piece.NONE : c; }
    private static int undoCastling(int u) { return (u >>> 4) & 0xF; }
    private static int undoEp(int u) { int e = (u >>> 8) & 0x7F; return e == 0 ? Square.NONE : e - 1; }
    private static int undoClock(int u) { return (u >>> 15) & 0xFFF; }

    /** True when a pawn of {@code byColor} stands beside {@code pawnSquare}, ready to take en passant. */
    boolean hasEpCapturer(int pawnSquare, int byColor) {
        int file = Square.file(pawnSquare), rank = Square.rank(pawnSquare);
        long theirPawns = pieces(byColor, Piece.PAWN);
        if (file > 0 && (theirPawns & (1L << Square.of(file - 1, rank))) != 0) return true;
        return file < 7 && (theirPawns & (1L << Square.of(file + 1, rank))) != 0;
    }

    /**
     * The ep square a FEN may keep, which is only one where the capture is real.
     *
     * A FEN can name any square. Trusting it let MoveGen emit an ep capture with
     * no pawn to capture: from "4k3/8/8/3P4/8/8/8/4K3 w - e6 0 1" the engine
     * played d5e6, the hash stopped matching Zobrist.compute, and unmake put a
     * black pawn on e5 that had never existed.
     */
    int validatedEpSquare(int ep) {
        if (ep == Square.NONE) return Square.NONE;
        int rank = Square.rank(ep);
        if (sideToMove == Piece.WHITE ? rank != 5 : rank != 2) return Square.NONE;

        // The pawn that just double-pushed must be sitting behind the ep square.
        int pushed = (sideToMove == Piece.WHITE) ? ep - 8 : ep + 8;
        if (mailbox[pushed] != Piece.index(Piece.other(sideToMove), Piece.PAWN)) return Square.NONE;

        return hasEpCapturer(pushed, sideToMove) ? ep : Square.NONE;
    }

    /**
     * Castling rights a FEN may keep: only those whose king and rook are home.
     *
     * Rights were trusted outright, and MoveGen generates castling from rights
     * alone. From "4k3/8/8/8/8/8/8/4K3 w KQ - 0 1", a board with no rooks at all,
     * the engine generated e1c1 and playing it produced 2KR3R: two white rooks
     * conjured out of nothing, because relocate() XORs bits that were clear.
     * With the king off e1 it threw ArrayIndexOutOfBoundsException instead.
     */
    int validatedCastling(int rights) {
        int ok = 0;
        int wk = Piece.index(Piece.WHITE, Piece.KING), wr = Piece.index(Piece.WHITE, Piece.ROOK);
        int bk = Piece.index(Piece.BLACK, Piece.KING), br = Piece.index(Piece.BLACK, Piece.ROOK);
        if ((rights & CASTLE_WK) != 0 && mailbox[Square.E1] == wk && mailbox[Square.H1] == wr) ok |= CASTLE_WK;
        if ((rights & CASTLE_WQ) != 0 && mailbox[Square.E1] == wk && mailbox[Square.A1] == wr) ok |= CASTLE_WQ;
        if ((rights & CASTLE_BK) != 0 && mailbox[Square.E8] == bk && mailbox[Square.H8] == br) ok |= CASTLE_BK;
        if ((rights & CASTLE_BQ) != 0 && mailbox[Square.E8] == bk && mailbox[Square.A8] == br) ok |= CASTLE_BQ;
        return ok;
    }

    /**
     * Passes the turn without moving a piece, for null move pruning.
     *
     * Illegal in chess, which is the point: if a side can forfeit a move and the
     * position is still good enough to fail high, it was far too good to need
     * searching properly.
     *
     * Three pieces of state move. Side to move flips. The en passant square is
     * cleared, because the right to capture en passant expires immediately and
     * leaving it set would let the opponent take a pawn that had two turns to
     * sit there. The halfmove clock is NOT reset: a null move is not a pawn move
     * or a capture, and resetting it would hide an approaching fifty-move draw.
     *
     * Pushed onto the same undo stack as a real move so {@link #unmakeNull}
     * is symmetric with {@link #unmake}, and so repetition detection sees a
     * distinct position rather than re-reading the previous one.
     */
    public void makeNull() {
        ensureCapacity();
        history[ply] = hash;
        undo[ply++] = packUndo(0xF, castling, epSquare, halfmoveClock);

        if (epSquare != Square.NONE) hash ^= Zobrist.EP_FILE[Square.file(epSquare)];
        epSquare = Square.NONE;
        sideToMove = Piece.other(sideToMove);
        hash ^= Zobrist.SIDE;
        halfmoveClock++;
    }

    public void unmakeNull() {
        int u = undo[--ply];
        hash ^= Zobrist.SIDE;
        sideToMove = Piece.other(sideToMove);
        if (epSquare != Square.NONE) hash ^= Zobrist.EP_FILE[Square.file(epSquare)];
        epSquare = undoEp(u);
        if (epSquare != Square.NONE) hash ^= Zobrist.EP_FILE[Square.file(epSquare)];
        castling = undoCastling(u);
        halfmoveClock = undoClock(u);
    }

    /**
     * True when the side to move has a piece other than pawns and the king.
     *
     * The zugzwang guard for null move pruning. In a king-and-pawn ending,
     * having to move is frequently a disadvantage, so "I passed and I am still
     * winning" stops implying "my real moves are also winning" and the pruning
     * rule inverts. Material is the cheap, standard proxy.
     */
    public boolean hasNonPawnMaterial(int color) {
        return (pieces(color, Piece.KNIGHT) | pieces(color, Piece.BISHOP)
                | pieces(color, Piece.ROOK) | pieces(color, Piece.QUEEN)) != 0L;
    }

    public void make(int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int flag = Move.flag(move);
        int piece = mailbox[from];
        int us = sideToMove;
        int them = Piece.other(us);

        int captured = Piece.NONE;
        if (flag == Move.EP_CAPTURE) {
            captured = Piece.index(them, Piece.PAWN);
        } else if (mailbox[to] != Piece.NONE) {
            captured = mailbox[to];
        }

        ensureCapacity();
        history[ply] = hash;
        undo[ply++] = packUndo(captured == Piece.NONE ? 0xF : captured,
                castling, epSquare, halfmoveClock);

        if (flag == Move.EP_CAPTURE) {
            int capturedSq = (us == Piece.WHITE) ? to - 8 : to + 8;
            remove(Piece.index(them, Piece.PAWN), capturedSq);
        } else if (captured != Piece.NONE) {
            remove(captured, to);
        }

        if (Move.isPromotion(move)) {
            remove(piece, from);
            put(Piece.index(us, Move.promoPiece(move)), to);
        } else {
            relocate(piece, from, to);
        }

        if (flag == Move.CASTLE_KING) {
            int rank = (us == Piece.WHITE) ? 0 : 56;
            relocate(Piece.index(us, Piece.ROOK), rank + 7, rank + 5);
        } else if (flag == Move.CASTLE_QUEEN) {
            int rank = (us == Piece.WHITE) ? 0 : 56;
            relocate(Piece.index(us, Piece.ROOK), rank, rank + 3);
        }

        hash ^= Zobrist.CASTLING[castling];
        castling &= CASTLE_MASK[from] & CASTLE_MASK[to];
        hash ^= Zobrist.CASTLING[castling];

        if (epSquare != Square.NONE) hash ^= Zobrist.EP_FILE[Square.file(epSquare)];
        // Only when an enemy pawn is actually placed to take it. Setting it
        // unconditionally XORs the ep file into the hash after every double push,
        // so two positions that are identical under FIDE rules get different
        // keys, and a threefold repetition spanning one of them is missed.
        epSquare = (flag == Move.DOUBLE_PUSH && hasEpCapturer(to, them))
                ? ((from + to) / 2) : Square.NONE;
        if (epSquare != Square.NONE) hash ^= Zobrist.EP_FILE[Square.file(epSquare)];

        boolean pawnMove = Piece.typeOf(piece) == Piece.PAWN;
        halfmoveClock = (pawnMove || captured != Piece.NONE) ? 0 : halfmoveClock + 1;
        if (us == Piece.BLACK) fullmove++;
        sideToMove = them;
        hash ^= Zobrist.SIDE;
    }

    public void unmake(int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int flag = Move.flag(move);

        sideToMove = Piece.other(sideToMove);
        int us = sideToMove;
        int them = Piece.other(us);
        if (us == Piece.BLACK) fullmove--;

        hash ^= Zobrist.SIDE;

        int u = undo[--ply];
        hash ^= Zobrist.CASTLING[castling];
        castling = undoCastling(u);
        hash ^= Zobrist.CASTLING[castling];
        if (epSquare != Square.NONE) hash ^= Zobrist.EP_FILE[Square.file(epSquare)];
        epSquare = undoEp(u);
        if (epSquare != Square.NONE) hash ^= Zobrist.EP_FILE[Square.file(epSquare)];
        halfmoveClock = undoClock(u);
        int captured = undoCaptured(u);

        if (flag == Move.CASTLE_KING) {
            int rank = (us == Piece.WHITE) ? 0 : 56;
            relocate(Piece.index(us, Piece.ROOK), rank + 5, rank + 7);
        } else if (flag == Move.CASTLE_QUEEN) {
            int rank = (us == Piece.WHITE) ? 0 : 56;
            relocate(Piece.index(us, Piece.ROOK), rank + 3, rank);
        }

        if (Move.isPromotion(move)) {
            remove(Piece.index(us, Move.promoPiece(move)), to);
            put(Piece.index(us, Piece.PAWN), from);
        } else {
            relocate(mailbox[to], to, from);
        }

        if (flag == Move.EP_CAPTURE) {
            int capturedSq = (us == Piece.WHITE) ? to - 8 : to + 8;
            put(Piece.index(them, Piece.PAWN), capturedSq);
        } else if (captured != Piece.NONE) {
            put(captured, to);
        }
    }

    /**
     * Has this position occurred {@code times} times already, counting the current one?
     *
     * Only positions since the last irreversible move can possibly repeat, because a
     * capture or a pawn move can never be undone by playing on. The halfmove clock
     * counts exactly that, so it bounds how far back to look.
     *
     * Positions two plies apart are the only candidates, since the side to move has
     * to match, hence the step of 2.
     */
    public boolean isRepetition(int times) {
        int seen = 1;
        int limit = Math.min(halfmoveClock, ply);
        for (int back = 2; back <= limit; back += 2) {
            if (history[ply - back] == hash && ++seen >= times) return true;
        }
        return false;
    }

    /** 100 plies, not 50 moves. A "move" is one from each side. */
    public boolean isFiftyMoveDraw() {
        return halfmoveClock >= 100;
    }

    /**
     * Positions where checkmate is impossible for either side no matter how badly
     * they play: bare kings, king and one minor piece, and king and bishop against
     * king and bishop on the same colour square.
     */
    public boolean isInsufficientMaterial() {
        if ((bb[Piece.index(Piece.WHITE, Piece.PAWN)] | bb[Piece.index(Piece.BLACK, Piece.PAWN)]) != 0L) return false;
        if ((bb[Piece.index(Piece.WHITE, Piece.ROOK)] | bb[Piece.index(Piece.BLACK, Piece.ROOK)]) != 0L) return false;
        if ((bb[Piece.index(Piece.WHITE, Piece.QUEEN)] | bb[Piece.index(Piece.BLACK, Piece.QUEEN)]) != 0L) return false;

        long knights = bb[Piece.index(Piece.WHITE, Piece.KNIGHT)] | bb[Piece.index(Piece.BLACK, Piece.KNIGHT)];
        long bishops = bb[Piece.index(Piece.WHITE, Piece.BISHOP)] | bb[Piece.index(Piece.BLACK, Piece.BISHOP)];
        int minors = Long.bitCount(knights) + Long.bitCount(bishops);

        if (minors <= 1) return true;                       // K v K, K+N v K, K+B v K
        if (minors == 2 && Long.bitCount(bishops) == 2) {
            // Two bishops on the same colour complex cannot mate.
            long dark = 0xAA55AA55AA55AA55L;
            boolean bothDark = (bishops & dark) == bishops;
            boolean bothLight = (bishops & ~dark) == bishops;
            return bothDark || bothLight;
        }
        return false;
    }

    /** ASCII diagram, white at the bottom. For eyeballing during step 1. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            sb.append(rank + 1).append("  ");
            for (int file = 0; file < 8; file++) {
                int p = mailbox[Square.of(file, rank)];
                sb.append(p == Piece.NONE ? '.' : Piece.toChar(p)).append(' ');
            }
            sb.append('\n');
        }
        sb.append("\n   a b c d e f g h\n");
        sb.append(sideToMove == Piece.WHITE ? "white" : "black").append(" to move\n");
        return sb.toString();
    }
}
