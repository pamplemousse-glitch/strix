# ADR 0014: NNUE, measured and rejected

**Date:** 2026-09-16
**Status:** rejected as the default evaluation. The inference ships and is tested;
no trained net is enabled.

## What shipped
Forward pass, incrementally updated accumulator, and integer quantization, all
hand-written and all tested. A net can be loaded at runtime with
`setoption name NetFile value <path>`.

## What did not
Any trained network. Two were measured against the piece-square tables:

| net | held-out loss | live hidden units | SPRT |
|---|---|---|---|
| first | 0.0086 | **1 of 256** | **-249.3 Elo** |
| after fixing the dead units | 0.0165 | 48 of 256 | **-330.5 Elo** |

## The bug, which was real and was not the problem

The first network had **254 of 256 hidden units dead**. Accumulator values spanned
[-15.31, 9.38] while the clipped ReLU's window is [0, 1]. The gradient through a
saturated clipped ReLU is exactly zero, so a unit that leaves the window can never
return. It was not a 256-neuron network, it was roughly a 2-neuron one.

The cause is scale. Up to 32 features are summed, and Adam grew the weights over
2.9 million updates until the sum dwarfed the activation window. The fix is to clip
feature weights during training, sized from `sqrt(32) * w`, which puts w near 0.09.
A first attempt at 0.02 was four times too tight and quadrupled the held-out loss.

**Fixing it made the engine worse**, from -249 to -330 Elo.

That is worth sitting with. The diagnosis was correct about the mechanism and wrong
about it being the constraint. A network whose activations are effectively binary
can still memorise a training set well, which is exactly what the lower loss on the
broken net was measuring.

## The actual constraint

**153,372 training samples for 197,000 parameters.** More parameters than examples.
Early stopping fires at epoch 4 because there is nothing further to learn from this
data, and no amount of activation tuning changes that.

The positions are also drawn from self-play by a ~1500-strength engine, so the
network never sees the positions a stronger engine reaches, even though the labels
themselves are sound (that was the ADR 0013 fix and it worked; the labels are not
what failed here).

## The guardrail, added afterwards

`Trainer` now computes independent samples divided by parameters before it trains
anything, and refuses below 10x. Independent samples are games, counted from the
game ids described above, not positions.

```
72 independent samples / 197,377 parameters = 0.00036x

REFUSING TO TRAIN.
```

`--force` overrides it. `--curve` trains on 10/30/60/100% of the games and reports
held-out loss for each, which separates the two cases every single number in this
ADR conflated: a curve still falling at 100% means more data helps and the slope
says how much, and a flat curve means data is not the constraint at all.

Both are cheap, and either would have ended the first attempt in minutes instead
of a day.

## What would be needed
1. **Orders of magnitude more positions.** Real NNUE training uses hundreds of
   millions. Leela's open data or a very long Stockfish labelling run.
2. **Positions from stronger play**, so the network sees the distribution it will
   actually be asked to evaluate.
3. A smaller network would fit this data better, and would also have less to offer
   than the piece-square tables it is competing with.

This is a data problem. The inference is correct and stays.

## A fourth thing, found later: the holdout was not a holdout

**Date added:** 2026-09-24

`Trainer` split its validation set randomly **by position**, and the class comment
defended it:

> the split here can be random, because each position carries its own independent
> Stockfish label rather than a result shared with 32 of its neighbours

The labels are independent. The positions are not. Two positions from the same
game are a couple of moves apart and share a pawn structure and a piece set, so a
random split drops near-duplicates of the training data into the validation set.

The holdout was therefore answering "can it evaluate positions it has almost
already seen", which is a much easier question than the one it was supposed to
answer. That is how 0.0086 held-out loss, the lowest number this project ever
recorded, sat next to -249 Elo.

`Texel` never had this bug. It grouped by runs of identical result labels and
split by game (ADR 0013). That trick does not transfer: Stockfish evaluations are
near-unique per position, so there is no run to detect, and `Label` writes from
several workers at once so a game's positions are not even adjacent by the time
the trainer sees them.

**Fix:** an explicit game id, written by `SelfPlay`, carried through `Label`, and
used by both `Trainer` and `Texel` to split by game. The format and its fallback
for older files live in `strix.tune.Dataset`. Verified on a fresh 20-game run:
the explicit id recovers 15 distinct games where the old label-run heuristic sees
9, because it merges consecutive games that ended the same way.

**How much the old split flattered itself: not cleanly measured.** On a
3,000-sample subset of `runs/labelled.txt`, a position-wise split reported 0.0159
held-out loss against 0.0224 for a game-wise one. Treat that as indicative only:
`labelled.txt` predates the game id, so the boundaries in that comparison were
reconstructed rather than real. A clean figure needs a regenerated dataset, and
would not change the decision either way, because the only verdict that counts is
still SPRT.

## The pattern, for the third time
| change | metric | reality |
|---|---|---|
| Texel tuning | error down 5.1% | **-57.6 Elo** |
| NNUE, broken | loss 0.0086, the best seen | **-249.3 Elo** |
| NNUE, fixed | live units 1 to 48 | **-330.5 Elo** |

Every improvement in a proxy metric coincided with a worse chess engine. The only
measurement that was ever right was several hundred games of chess.

That is the return on building the harness, and it is worth more than a shipped net
would have been.
