# ADR 0008: Move ordering

**Date:** 2026-09-15
**Status:** accepted

## What I chose
In priority order: transposition table move, then captures by MVV-LVA (most
valuable victim, least valuable attacker), then two killer moves per ply, then a
history heuristic indexed by piece and destination.

Selection sort one move at a time rather than sorting the whole list, because most
nodes cut off after a few moves and sorting the tail is wasted work.

## What else I considered
Static Exchange Evaluation for captures instead of MVV-LVA, which is more accurate
and more expensive. Deferred until it can be measured.

## Why
Alpha-beta only cuts off when it finds a refutation, so the entire value of
pruning depends on trying good moves first. This is not a refinement, it is the
difference between alpha-beta working and not working.

**Measured, nodes to reach depth 6 from the starting position:**

| configuration | nodes | speedup |
|---|---|---|
| bare alpha-beta | 3,500,451 | 1.0x |
| + transposition table | 2,384,099 | 1.5x |
| + move ordering | 126,390 | **27.7x** |
| + both | 89,109 | **39.3x** |

The striking result is that **the table alone is worth 1.5x while ordering is
worth 27.7x.** The transposition table's real contribution is not its cutoffs, it
is that it supplies a good first move to try. That matches Rustic's published
split of +42 Elo from TT cutoffs and +100 Elo from TT move ordering.

At depth 6 on Kiwipete, bare alpha-beta did not finish inside a 400 second budget
at all.

## What it costs
Killers and history are per-search mutable state, cleared at the start of each
`think`. History values grow without bound over a long search and are not aged,
which is a known gap that needs measurement to tune.
