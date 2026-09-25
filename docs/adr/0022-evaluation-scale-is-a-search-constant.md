# ADR 0022: The evaluation's scale is a search constant

**Date:** 2026-09-25
**Status:** accepted

## What was found

Delta pruning in quiescence reads:

```java
if (standPat + gain + DELTA_MARGIN < alpha) continue;
```

`standPat` and `alpha` come from whatever `Evaluator` is installed. `gain` comes
from `Material.VALUE` and `DELTA_MARGIN` was a literal 200. Those are the same
unit only while the evaluator agrees that a pawn is 100 centipawns.

Piece-square tables do agree. A trained network does not: its output scale is
whatever its training objective produced. One measured here priced a pawn at
**31 cp** against the tables' ~100, a 3.2x compression, and a second at **-20**.

So the right-hand side stopped ever winning and the prune silently switched
itself off. Measured, nodes at fixed depth 4 with transposition table and
ordering on:

| position | piece-square tables | network | after the fix |
|---|---|---|---|
| Kiwipete | 6,498 | **1,017,488** | **9,997** |
| middlegame | 7,003 | 50,294 | 38,801 |

**157x**, from a unit mismatch, with no error anywhere and nothing logged.

At the node budget the rejected SPRT actually used (20,000 per move), the
network completed a mean depth of **3.75** against the tables' **5.18**. It was
playing 1.4 plies shallower before any question of whether its evaluation was
any good, and a fixed-node test cannot see that at all.

## The fix

`Evaluator` gains `pawnValue()`, defaulting to 100. Search converts margins
expressed in material centipawns through it:

```java
int scaled = (gain + DELTA_MARGIN) * evaluator.pawnValue() / 100;
```

`NnueEvaluator` measures its own at load: each of five probe positions is
evaluated with and without one pawn, and the median difference is the answer.
The result is clamped to [10, 1000], because this is asked of half-trained nets
too and one of them answered -20; an unclamped negative would invert every
margin derived from it.

## What else is coupled, and what is not

Audited every constant in `Search` that meets an evaluation score:

| constant | invalidated by an evaluator swap? |
|---|---|
| **delta pruning margin + `Material.VALUE` gain** | **yes, and it was live. Fixed** |
| `FUTILITY_MARGIN` | yes, but the flag is off (ADR 0018) |
| `ASPIRATION_WINDOW` | yes, but the flag is off (ADR 0018) |
| `standPat >= beta`, `standPat > alpha` | no, both sides come from the evaluator |
| `MATE`, `INFINITY`, the mate-score bounds | no, compared against `MATE`, not the evaluation |
| `LMP_COUNT`, the LMR reduction table | no, move counts and depths only |
| `Ordering` and `See` material values | no, internally consistent, and never compared to the evaluator except through delta pruning |

One live constant, two dormant ones. The reason a single constant produced a
157x blowup is that it lives inside quiescence, which is where most of the tree
is.

Also coupled, and **not yet fixed**: the harness's own adjudication thresholds
(`Game.RESIGN_SCORE = 600`, `DRAW_SCORE = 20`) are compared against engine
centipawns. Under a compressed evaluation `|eval| >= 600` occurs in 0.2% of
positions against 14% for the tables, so in a mixed-scale match only one side
can ever resign.

## The general rule this establishes

**Swapping the evaluation is not a local change.** Anything that compares a
score against a fixed number is part of the evaluation's contract, and a new
evaluator either has to honour the old scale or the constants have to move with
it. The failure mode leaves no error behind: the search still runs, still
returns legal moves, and simply searches a fraction as deep.

That is also why a net must never be judged before this is checked. The rejected
net was bad for its own reasons, and it was additionally being measured through
a disabled quiescence prune, so the 26-0 could not distinguish the two.
