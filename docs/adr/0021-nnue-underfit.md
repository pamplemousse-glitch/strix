# ADR 0021: The second NNUE lost 26-0, and why

**Date:** 2026-09-25
**Status:** rejected. No trained net is enabled. The trainer needs mini-batching.

## What was fixed since ADR 0014, and it was not enough

ADR 0014 rejected the first net at -330 Elo and concluded the constraint was
data volume: 153,372 positions from ~6,000 correlated self-play games, labelled
at depth 8 by a ~1500-strength engine.

All of that was addressed:

| | First attempt | This attempt |
|---|---|---|
| Independent games | ~6,000 | **2,066,513** |
| Samples per parameter | 0.80x | **10.5x** |
| Label search | depth 8 (~1,600 nodes) | **1,000,000 nodes**, median depth 21 |
| Label source | its own weak self-play | Lichess fishnet |
| Dead hidden units | 254 of 256 | **0 of 256, every epoch** |
| Holdout split | by position | by game |

And the net still lost **26 games to 0** against the piece-square tables it was
meant to replace.

## The diagnosis, which is not subtle

```
position                   NNUE       PSQT
white up a QUEEN            -35        895
black up a QUEEN            -83       -895
white up a ROOK              -9        500
white up 3 pawns             17        325
```

The net's entire output range across these positions is about [-157, +17]. **It
cannot distinguish being a queen up from being a queen down.** It has not learned
material, which is the easiest thing in chess to learn and the first thing any
evaluation must know.

The loss said the same thing more quietly. Validation settled at 0.068984
against 0.0735 for predicting a constant 0.5, so the net is **6% better than a
constant**. It is not broken, it is barely trained.

## Root cause: the learning rate is trapped between two failures

This trainer updates the optimizer once per **sample**. Reference NNUE trainers
update once per **batch of 16,384**. That single difference creates a vice:

- **Too high and the units die.** At LR 0.003, four orders of magnitude more
  steps per epoch drive the weights into their clip bounds inside one epoch. The
  gradient through a saturated clipped ReLU is exactly zero, so a unit that
  leaves the window never returns. Measured: 211 of 256 dead, and a validation
  loss identical to predicting a constant.
- **Too low and it underfits.** At LR 5e-5 every unit stays alive for all 11
  epochs and the net learns almost nothing, which is this ADR.

There is no rate that both keeps the units alive and learns at a useful pace,
because the step count is wrong by four orders of magnitude. Lowering the rate
to survive the step count also divides the progress per epoch by the same factor.

**Mini-batching is not an optimization here, it is the missing mechanism.**
Accumulating gradients over 16,384 samples and applying one update does two
things at once: it cuts the optimizer steps per epoch by 16,384x, which removes
the pressure that kills units, and it averages the gradient, which makes a much
larger rate safe.

## What ADR 0014 got wrong, stated plainly

It concluded "this is a data problem". Data was *a* problem and it is now fixed,
and the net is still worthless. The trainer was also broken, in a way that no
amount of data could have fixed, and the evidence for it was available at the
time: 254 of 256 units dead is not what insufficient data looks like.

The reason it was missed is that the health metric sampled a single position,
which cannot distinguish a saturated net from a saturated position. Walking a
few thousand positions per epoch makes the failure obvious in epoch one.

## What is kept

Everything except the net. The data pipeline, the game-wise holdout, the ratio
guardrail, the health instrumentation and the inference are all correct and all
retained. The inference in particular has never been implicated: it passes a
from-scratch comparison, an incremental-versus-refresh check across make and
unmake, and a mirror-symmetry test that shares no code with the net.

## Next

1. Mini-batching in `Trainer`, with the batch size as the tuned knob.
2. Retrain at a rate that is actually able to learn.
3. The material probe above is the cheap gate: a net that cannot price a queen
   is not worth an SPRT.
4. SPRT at fixed nodes, then **at a fixed clock**, because the net runs at 45% of
   the piece-square-table nodes per second and fixed-node testing hides that
   entirely.
