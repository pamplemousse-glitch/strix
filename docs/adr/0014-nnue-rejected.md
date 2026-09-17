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

## What would be needed
1. **Orders of magnitude more positions.** Real NNUE training uses hundreds of
   millions. Leela's open data or a very long Stockfish labelling run.
2. **Positions from stronger play**, so the network sees the distribution it will
   actually be asked to evaluate.
3. A smaller network would fit this data better, and would also have less to offer
   than the piece-square tables it is competing with.

This is a data problem. The inference is correct and stays.

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
