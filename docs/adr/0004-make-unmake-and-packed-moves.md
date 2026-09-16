# ADR 0004: Make/unmake in place, moves packed into an int

**Date:** 2026-09-15
**Status:** accepted

## What I chose
Two decisions that go together.

**Moves are a packed `int`:** 6 bits from, 6 bits to, 4 bits flag.

**make/unmake mutates the board in place** and reverses it, with an undo record
(captured piece, castling rights, en passant square, halfmove clock) packed into
a single `int` on a per-ply stack.

## What else I considered
A `Move` record or class, and copy-make (clone the whole board per move, never
undo anything).

## Why
A `Move` object per node means millions of allocations per second in the search
loop. Copy-make means copying twelve bitboards plus the mailbox at every node.
Both are pure overhead in the hottest code in the program.

Copy-make's real appeal is that it cannot have undo bugs. That risk is covered
here instead by perft, which exercises make/unmake on every node, including the
leaves.

## What it costs
Undo bugs become possible, and they are nasty: the symptom appears later and
elsewhere. Two mitigations are in place.

`Perft.count` deliberately does **not** short-circuit at depth 1. Returning the
legal move count directly is correct and roughly 3x faster, but it would skip
make/unmake across roughly 97% of the tree, which is exactly where undo bugs would
otherwise hide.

Castling rights update as `castling &= CASTLE_MASK[from] & CASTLE_MASK[to]`. The
`to` half is the one everybody forgets: it handles an enemy rook being **captured**
on its home square. Verified by deliberately removing it, which broke Kiwipete,
Position 5, and the flip-board symmetry test while leaving the other four green.
