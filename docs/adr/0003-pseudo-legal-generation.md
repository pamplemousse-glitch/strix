# ADR 0003: Pseudo-legal generation with a legality filter

**Date:** 2026-09-15
**Status:** accepted

## What I chose
Generate every move ignoring king safety, then make each one, discard it if it
leaves our own king attacked, and unmake.

## What else I considered
Fully-legal generation: compute pinned pieces and check evasions up front and
never emit an illegal move at all. This is what strong engines do.

## Why
Fully-legal generation is faster, because it avoids make/unmake on moves that get
thrown away. But it is significantly harder to prove correct, and correctness is
the entire point of Stage 1. Pseudo-legal plus a filter has one obvious invariant
(the king is not attacked after the move) instead of a pin-detection algorithm
that can be subtly wrong in ways perft finds only at depth.

## What it costs
Wasted make/unmake on every illegal move. Measured impact is unknown and will stay
unknown until Stage 3 exists to measure it, which is the honest answer to "why
not the faster one."

One exception was unavoidable: **castling through check must be validated during
generation.** After the move the king already sits on its destination square, so
the filter can only catch "castled into check," never "castled through check."
