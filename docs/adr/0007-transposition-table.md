# ADR 0007: Transposition table

**Date:** 2026-09-15
**Status:** accepted

## What I chose
64MB direct-mapped table keyed by Zobrist hash. Full 64-bit key stored and
compared. Depth-preferred replacement. Entry packed into one long: move, score,
depth, bound flag.

## What else I considered
Always-replace, and bucketed replacement with several entries per slot.

## Why 64-bit keys are enough
Only the low bits index the table, so two positions can land in the same slot.
Storing the full key means a wrong hit needs a genuine 64-bit collision, which at
any table size reachable here is rare enough to ignore. The engine is also written
so a bad entry costs accuracy, never legality: **the stored move is always
re-validated by the move generator**, so a corrupt entry cannot produce an illegal
move.

## Mate scores
Mate scores are relative to the ply they were found at, so they are stored as
absolute distances and converted back on retrieval. Skipping this makes an engine
announce mate in 3 forever without ever delivering it.

## What it costs
A 64MB allocation, and a class of bug where the search silently reads another
position's result. Guarded by the Zobrist invariant test: the incrementally
maintained hash is compared against a from-scratch recomputation at every node of
a depth-4 walk, through both make and unmake.

That test is the same shape as the one Stage 4's NNUE accumulator will need.
