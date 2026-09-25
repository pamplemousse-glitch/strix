# ADR 0017: The engine returned no move, and quiescence is why

**Date:** 2026-09-24
**Status:** accepted. Supersedes the causal claim in ADR 0015.

## ADR 0015 was right about the symptom and half right about the cause

It recorded that of the first six rated games, four were won by mate and both
losses were on time, and blamed a dropped move POST. That was real, and fixing
it was necessary. It was not the whole story.

## The engine could return `Move.NONE` on a perfectly ordinary clock

`Search.think` initialised `completedMove = Move.NONE` and discarded any
unfinished iteration with `if (stopped) break`. If iteration 1 did not finish,
nothing was ever assigned and `bestMove` came back `NONE`.

`LichessBot.playGame` then does `if (search.bestMove == Move.NONE) return;`. No
move is posted, so the position never changes, so no new `gameState` arrives, so
nothing recomputes. The bot sits on a healthy stream until it flags.

That is the identical silent shape as the dropped POST, reached by a different
route, and it is the third time this project has met it.

Reproduced on Kiwipete with a **fresh 3+2 clock**, a 7000 ms budget:

```
elapsed 7002 ms, bestMove = NONE
```

`uci/Main` hid it in GUI play with a fallback that plays `m[0]`, the first
generated legal move, which is a different way to lose.

## Why iteration 1 could not finish

`quiescence` searched captures in raw generation order. No ordering, no delta
pruning, no cap. Two things made that catastrophic rather than merely slow:

1. `MoveGen.generateCaptures` internally runs a full `generateLegal`, so every
   q-node pays make/unmake plus `isAttacked` for every pseudo-legal move.
2. At depth 1 every leaf inherits the root window, so `beta` is `+INFINITY` and
   `standPat >= beta` can never fire. **There was no beta cutoff anywhere in the
   capture tree.**

Measured on Kiwipete with TT and ordering enabled:

| | before | after |
|---|---|---|
| depth 1 | 28,212 ms | **6 ms** |
| depth 2 | 32,660 ms | **16 ms** |
| 3+2 clock | NONE after 7002 ms | `d5e6` in 4439 ms |

Depth 1 cost more than depth 2, which is the tell. No budget under about 29
seconds could complete iteration 1 in that position, so on any blitz clock the
engine returned nothing at all.

Quiet positions were fine. Tactical ones were not, which is why 120 random
self-play positions showed no failures while four of six capture-dense positions
burned the entire budget.

## What changed

- **`completedMove` starts at `rootMoves[0]`.** Not a good move, a legal one,
  and a legal move is worth infinitely more than no move. `rootCount == 0` is
  guarded separately, and is the only place `NONE` is still the honest answer.
- **Quiescence orders its captures** through the existing `Ordering.pickBest`.
- **Delta pruning** with a 200 cp margin, deliberately generous because it
  prunes on material alone and would otherwise discard real sacrifices.
- **Quiescence no longer stands pat in check.** Standing pat claims the option
  of declining every capture, which a side in check does not have. It now
  generates evasions and reports mate, instead of scoring a checking sacrifice
  as plain material loss.

## Two others found alongside

**`negamax` was not a reference implementation.** `alphaBeta` applied
repetition, fifty-move and insufficient-material rules and `negamax` did not, so
the two were not computing the same function. The class comment says "if the
scores ever differ, alpha-beta has a bug, full stop", and it was the reference
that was wrong. Four of 150 random positions disagreed at depth 4; removing only
those three predicates from `alphaBeta` took the count to zero, which pins it.

`SearchTest` asserted this invariant against six hand-picked FENs, none of which
reach a draw rule within four plies. The test now also runs a corpus.

**A missing clock meant an unlimited search.** `wtime`/`btime` default to -1 and
`allocate` returned `Long.MAX_VALUE`, so `go btime 60000 binc 2000` with white to
move searched to depth 63 and never answered. It now borrows the opponent's clock
and falls back to a fixed slice.

Move overhead also went from 100 ms to 300 ms: the server counts from before the
`gameState` event is sent, through our think, and through a move POST that is
itself retried up to three times.

## What is not claimed

The node and time figures above are measured. **No Elo claim is attached**, on
purpose. Measuring it needs a UCI switch for the new behaviour and a harness run,
and after ADR 0016 the harness is only now capable of producing evidence that
means anything. Until that run happens this is a bug fix with a stopwatch behind
it, not a strength claim.
