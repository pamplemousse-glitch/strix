# ADR 0013: Texel tuning, measured and rejected

**Date:** 2026-09-16
**Status:** rejected. The tuned values are NOT shipped.

## What I expected
Rustic's published figures put tapered, tuned evaluation at +270 to +300 Elo, and
it needs no games to run. It looked like the cheapest large gain available.

## What happened

| | |
|---|---|
| Training error | 0.08897 -> 0.08706 |
| Held-out error | 0.08902 -> 0.08823 |
| Tuned tables | clean, symmetric, chess-shaped |
| **SPRT vs untuned** | **-57.6 Elo, H0_ACCEPTED, 560 games, LLR -3.06** |

Every intermediate signal was positive. The engine got materially worse.

## Why

**The training data is self-play from the untuned engine.** Every position came
from games played at 4,000 nodes by an engine with the very evaluation being
tuned. At that strength games are decided by blunders far more often than by the
positional features the tuner adjusts, so the label "this position led to a win"
is only weakly connected to "this position is good". The tuner learned to predict
that engine's blunders.

**The starting values were already excellent.** Knight 320, bishop 330, rook 500
are not arbitrary. They are the product of the whole chess world tuning them for
decades. One pass of coordinate descent over 2,110 self-played games is not going
to improve on that, and it moved them to 312, 322 and 492.

**Effective sample size was far smaller than it looked.** 198,014 positions came
from 6,000 games. Positions within a game share a result label, a pawn structure
and a set of pieces, so the effective count is nearer 6,000. That is about 13
independent samples for each of 453 parameters.

## What would be needed to make it work
1. Labels from a **stronger** source: Stockfish evaluations, or Leela's open data,
   rather than this engine's own games.
2. Far more independent games, sampling 1 to 3 positions from each rather than 33.
3. Proper quiet-position filtering by running quiescence and tuning on the
   resulting position, not by checking whether the best move is a capture.

None of that is hard. It is a data problem, not a tuner problem, and the tuner is
kept in the repo because it works correctly.

## The consequence for Stage 4
**NNUE cannot train on self-play data from this engine.** If 453 parameters could
not be fitted from it, a network with millions of weights certainly cannot. Stage 4
uses Leela's open dataset or Stockfish-labelled positions.

That conclusion is the actual return on this experiment, and it was only available
because the harness could say no.

## What it cost
A day, and the result is a negative one that is worth more than a fabricated
positive. Every intermediate metric said the change was good. The only thing that
disagreed was several hundred games of chess.
