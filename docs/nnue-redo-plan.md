# NNUE, second attempt: the plan

*Written 2026-09-24, after ADR 0014 rejected the first net at -330 Elo.*

## What the research changed

Four things were investigated before writing this: the state of the art in NNUE
architecture, where training data comes from, what the two strong Java engines
actually did, and how quantization and inference are verified.

**1. The architecture is already right.** Calvin 4.0.0 shipped
`(768 -> 256)x2 -> 1` and gained roughly **+417 Elo in self-play**, removing its
hand-crafted eval entirely. That is byte-identical in shape to what this repo
already has. Serendipity's first NNUE gained **+102 Elo** at a similar size.

Plain 768 is not a limitation. HalfKP is a Shogi-derived artifact, and bullet's
own documentation says first networks should be `(768 -> N)x2 -> 1` with no
buckets and one hidden layer. Nothing about the input encoding needs to change.

**2. The quantization constants already match.** `QA = 255`, `QB = 64`,
`SCALE = 400`. Identical to Calvin, Serendipity and bullet's `simple.rs`. A
mismatch here is a classic silent killer and this repo does not have it.

**3. Three of the four suspected training bugs are absent.**

| Suspected cause | Status |
|---|---|
| Target was raw centipawns, not `sigmoid(cp/SCALE)` | **Absent.** `Trainer` builds `sigmoid(cp/400)` |
| `[0,1]` clamp missing from the TRAINING forward pass | **Absent.** Clamped in both directions |
| Feature weights initialised for dense inputs | **Absent.** `uniform(+/-0.0177)`, accumulator sigma ~0.058, tighter than nnue-pytorch's 0.12 |
| Weight clipping sized as `sqrt(32)*w` | **Real, and inverted.** See below |

The `sqrt(32)` sizing multiplied where it should have divided. For 32 active
features of standard deviation `s`, the accumulator standard deviation is
`sqrt(32)*s`, so keeping it near 1 wants `s ~ 1/sqrt(32) = 0.177`. Multiplying
loosened the bound by 5.66x in the wrong direction. It is moot now: the
initialisation is already correct and `FEATURE_CLIP = 0.09` bounds the
accumulator at 32 * 0.09 = 2.88.

**4. Inference passes a test that does not depend on `featureIndex`.** Every
previous NNUE test compared `Network`, `Accumulator` and `Quantized` against each
other, and all three call `Network.featureIndex`, so a perspective bug would make
all of them agree and all of them pass. `NnueTest.mirroredPositionEvaluatesTheSame`
colour-swaps and flips the board through the FEN, sharing no code with the net,
and it holds.

**Conclusion: ADR 0014's diagnosis stands. The constraint is data.** The first
net saw 153,372 positions drawn from about 6,000 independent games played by a
~1500-strength engine. Nothing else found so far explains -330 Elo, and the
failure signature is consistent with a net that had nothing to learn from.

## The evidence on how much data is actually needed

| Engine | Architecture | Positions | Result |
|---|---|---|---|
| Small engine (TalkChess) | 768 -> 32 | 20M | ~+200 Elo |
| Prokopakop | 768-based | ~100M | +200 Elo |
| casanchess 2.0 | replaced HCE | 170M | +230 self-Elo |
| **Calvin 4.0.0** | **(768 -> 256)x2 -> 1** | **250M** | **~+417 self-Elo** |
| Leorik 3.0 | (768 -> 256)x2 -> 1 | 622M | replaced HCE |
| Serendipity PR #18 | (768 -> 384)x2 -> 1 | 600M | +201 Elo |

nodchip's rule of thumb is at least 10x the parameter count, which for 197,377
parameters is 2M positions. The empirical record says that rule is far too
generous: **20M is where it works at all, 100M+ is where it reliably beats a good
hand-crafted eval.** This repo had 0.15M.

## Plan

### Phase 0: close the attribution gap (half a day)

Today a bad result cannot be attributed to the net or to the inference. Both look
like negative Elo. Two of the four rungs already exist; build the rest.

| Rung | What it proves | Status |
|---|---|---|
| Naive float reference vs quantized integer path | Quantization and scaling are right | Partial: `NnueTest` compares them |
| Incremental accumulator == from-scratch, bit-exact | Delta updates are right | **Exists** |
| Colour-swap mirror invariant | Perspective and indexing are right, independent of `featureIndex` | **Added 2026-09-24** |
| Dead-unit histogram per checkpoint | Training health, live | **Missing, build this** |

The dead-unit metric is the one that would have caught the first attempt before a
single game was played: over a few thousand positions, log each unit's
pre-activation min, max and mean, and count units never landing strictly inside
`(0, QA)`. Log it every epoch, not at the end.

### Phase 1: real data (half a day)

Use the **Leela Chess Zero open data**, which is what both Calvin and Serendipity
train on. Calvin used the Kaggle mirror
`linrock/t77dec2021-t78janfeb2022-t80apr2022` and **re-scored it with his own
search** rather than trusting Leela's eval.

Target **100M positions**, sampling 1 to 3 per game. The `Dataset` format, game
ids and the ratio guardrail all already exist to consume it.

The Lichess monthly PGN dumps are the fallback: free, but only a fraction of
games carry `%eval` and the evals are shallower.

### Phase 2: train (hours, not days)

**Keep the Java trainer.** Calvin abandoned his own Python trainer for bullet on
speed grounds, but this repo is explicitly a from-scratch project and the trainer
is not the bottleneck: at roughly 8,000 operations per sample, 100M samples is
about 13 minutes per epoch single-threaded, so ten epochs is an evening.

Run `--curve` first on 10/30/60/100% of the data. A curve still falling at 100%
says get more; a flat one says something else is wrong. That diagnostic exists
and has never been used on real data.

If the Java trainer underperforms, **bullet** is the fallback, and its
`examples/simple.rs` is this exact architecture with these exact constants. Its
output format is a flat little-endian `int16` stream: feature weights, feature
biases, output weights, output bias. Calvin's loader reads precisely that, so
porting is a day at most.

### Phase 3: verdict

SPRT against the piece-square-table build, `[0, 100]` bounds to settle fast.
After ADR 0016 the harness finally produces evidence that means something.

Ship only if positive. Write ADR 0018 either way.

## Sequence after a working net

The order in which both Java engines actually found the Elo, each SPRT'd
separately:

1. **Hidden size.** Serendipity got **+199 and +201** from two size bumps. This
   dwarfs everything else. 256 -> 384 -> 512 -> 1024.
2. SCReLU instead of clipped ReLU (+21 for Serendipity).
3. Output buckets by piece count (+6).
4. King input buckets (+11) and horizontal mirroring.
5. `jdk.incubator.vector` last, behind a `vectorBitSize() >= 256` check with a
   scalar fallback. Serendipity ran scalar NNUE for nine months.

Note the shape of that list: architecture cleverness is worth tens of Elo, and
network size is worth hundreds. Do them in that order or waste the time.

## The open risk

Serendipity shipped a **broken accumulator for a full day while passing a +102
Elo test**, and fixed it in PR #17 by adding 323 lines of accumulator tests that
had not existed. A subtly wrong accumulator still evaluates plausibly. Phase 0 is
not optional, and it is cheap.
