# ADR 0001: Bitboards over a mailbox array

**Date:** 2026-09-15
**Status:** accepted

## What I chose
One 64-bit `long` per piece type and color, twelve in total, plus a 64-entry
mailbox array kept in sync for "what piece is on this square".

## What else I considered
A plain mailbox-only board: `int[64]` or the classic 0x88 `int[128]`. Simpler to
write, far easier to read in a debugger.

## Why
Move generation is the hot path. Search calls it millions of times per second, so
the board representation dominates everything. Java compiles
`Long.numberOfTrailingZeros` and `Long.bitCount` down to single CPU instructions
(TZCNT, POPCNT), so bit-parallel operations over a whole piece set cost roughly
what a single square lookup does.

Keeping the mailbox alongside is not redundancy. Without it, answering "what did I
just capture" means scanning twelve bitboards on every capture.

## What it costs
Harder to get right, and one Java-specific trap: `long` is signed and there is no
unsigned 64-bit type, so logical shifts must use `>>>` and never `>>`. A `>>`
sign-extends and silently corrupts the board. Perft catches it, but only if you
remember that is what you are looking at.
