# ADR 0009: Repetition detection by scanning a key stack

**Date:** 2026-09-16
**Status:** accepted

## What I chose
An array of Zobrist keys indexed by ply, pushed in `make` and implicitly popped in
`unmake`. Detection scans backward in steps of 2, bounded by the halfmove clock.

## What else I considered
The Chess Programming Wiki documents three approaches:

1. **List of hash keys** (what I built)
2. A dedicated small hash table, bloom-filter style, roughly 16KB
3. Flagging entries in the existing transposition table

Separately, Stockfish implements Marcel van Kervinck's **cuckoo algorithm for
upcoming repetition detection**, which spots a cycle one ply before it occurs so
the draw is found an iteration earlier in the search.

## Why
Checked after building it rather than before, and it turned out to be the
recommended default. CPW calls the key-stack method the most common, citing
simplicity and **compatibility with parallel search**, since each thread keeps its
own game record. Option 3 is explicitly warned against for hash-collision and
multiprocessor reasons.

The parallel property matters here specifically: step 18 runs games across workers.

Two details are load-bearing and both come straight from CPW:

- **Bound the scan by the halfmove clock.** A capture or pawn move can never be
  undone, so nothing before the last one can possibly repeat. The clock already
  counts exactly that, so the bound is free.
- **Step by 2.** A repetition requires the same side to move.

The cuckoo upgrade is an addition on top, not a replacement, and its value is
disputed: there is a TalkChess thread titled "Cuckoo hashing for repetition
detection... no gain?". It is precisely the kind of change that needs SPRT to
justify, so it waits.

## Inside the search, two occurrences count as a draw
Three-fold is the rule of chess, but inside a search a single repetition already
means the side to move can force the draw. Proving it twice wastes depth.
`GameResult` adjudication uses three; the search uses two.

## What it costs
A 1024-long array per board and a scan bounded by the halfmove clock. Both
negligible. Detection is exact rather than probabilistic, unlike the bloom-filter
approach.
