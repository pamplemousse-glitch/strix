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

## The finding that reorders the whole plan

**PeSTO evaluates with piece-square tables and nothing else, and it is rated 2988
CCRL Blitz.** Its author: *"there is a tempo bonus for the side to move, and
that's it, no other chess knowledge is present in PeSTO."*

Strix is at ~1650 with piece-square tables. **It is not eval-limited. It is
search-limited by roughly 1300 Elo.**

What actually lives at ~1650 CCRL: Rustic Alpha 1 (no transposition table),
Stash v10/v11, and Lynx 0.13 — which had *more* eval than this engine (pawn
structure, king safety, mobility) and still sat at 1632 for want of a TT.

Measured ablation (Delorme's Dumb, removing one feature at a time):

| Removed | Elo | Present here? |
|---|---|---|
| MVV-LVA | -495 | yes |
| Transposition table | -283 | yes |
| Late move reductions | **-229** | **no** |
| Quiescence | -145 | yes |
| Null move | **-116** | **no** |
| Aspiration windows | **-101** | **no** |
| History | **-94** | **no** |
| Late move pruning | **-38** | **no** |

That is roughly 600 Elo of measured, well-understood search work requiring no
training data at all.

### A first NNUE can be, and often is, negative

| Engine | First net | Result |
|---|---|---|
| **Bagatur** | HalfKP via JNI | **-80 Elo**, at 12x slower nps |
| **Svart** net 0003 | wdl 0.3, 80 epochs | **-63.2 +/- 97.4** |
| **Svart** net 0001 | data from an opening book | **equal to HCE** |
| **NoaChess** | HalfKAv2_hm, 13M positions | **+4.5 +/- 11.4** (zero is inside the interval) |
| **Carp** net 0001 | 384 hidden, 100M fens | **-9.2** |
| Carp net 0003 | *same 384 hidden*, 230M fens | **+22.5** |

Carp 0001 vs 0003 is the cleanest controlled experiment in the corpus:
identical architecture, and the only variable that moved was data volume.

bullet's own documentation predicts it: *"an engine may (and likely will for a
beginner) actually LOSE elo with an SF architecture vs a much simpler one."*

### Three failures that were not about data, and each was invisible

1. **Quantization.** NoaChess shipped a net with **85.6% of its feature
   transformer quantised to exactly zero**, "evaluating 16.6% away from the
   network that was trained". Fixing it was worth **+195 Elo with the engine
   binary untouched**. Its engine-vs-trainer parity tests passed the whole time:
   *"they verify that engine and trainer compute the same thing, not that the
   thing is good."*
2. **Eval scale, and this one applies directly here.** Neutron-o1 retrained a net
   that improved on every metric, and it **lost 38 Elo**, because it was now
   correctly calibrated while the search's pruning margins had been tuned for
   years against a net that understated. One output-gain constant moved **~100
   Elo**, and the bug had silently **inverted the sign of five earlier
   architecture conclusions**. Any NNUE dropped into this engine changes the
   eval distribution that every future futility, razoring and LMR margin is
   tuned against.
3. **Inference cost.** Leorik's naive C# NNUE ran at **50K nps, 100x slower**
   than its hand-crafted eval. Bagatur needed a pure-Java rewrite to get from
   -80 to +100, and incremental updates only 16 months later for another +30.

### Java specifics, measured

- Serendipity ran **scalar** NNUE for nine months before the Vector API.
- Calvin's CReLU to SCReLU switch cost **41% of nps** (1.7M to 1.0M), recovering
  to 1.4M after optimisation. Prefer CReLU while nps is the constraint.
- Calvin **abandoned** a PR splitting inference behind an interface: 22% nps.
  If that pattern is used, the field must be `static final` with exactly one
  implementation loaded, or the call site goes bimorphic.
- Discard the first two `bench` runs; JIT makes run 1 meaningless.
- This machine is Intel AVX2, 256-bit vectors, so the Vector API path is
  available. It is not on Apple Silicon, where it cannot emit `SDOT`.

### Rating-list gains are about half of self-play gains

Pedantic: +161 self-play at 20+0.2, **+95 CCRL**. akimbo: +388 self-play,
**+309**. Midnight: +352 self-play, **+237**. And within self-play the gain
shrinks with time control (Pedantic 161 to 142 to 125), which is the signature
of an eval that is better per node and costs nodes.

**Testing NNUE at 1650 will produce a large self-play number whether or not the
net is any good**, masking both the net's defects and the search bugs beneath it.

## Recommended order

1. **Search.** Null move, LMR, aspiration windows, history, PVS, LMP/futility.
   Each SPRT'd separately on the harness fixed in ADR 0016. ~600 Elo of measured
   work, no data required.
2. **Re-measure.** Against a known ladder (Stash v11 = 1690, v14 = 2060) rather
   than only Lichess, so the number means something.
3. **Then NNUE**, on the plan below. The already-written inference is not wasted
   work; it converts far better from 2900 than from 1650.

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

### Phase 1: real data (about ten minutes)

**`Lichess/fishnet-evals` on HuggingFace.** Every `%eval` from the monthly
Lichess dumps, already extracted to Parquet. No PGN parsing, ever. CC0, no
attribution and no account required.

| | |
|---|---|
| Total | 34,463,231,919 rows, one file per month |
| `standard_rated_2018_01.parquet` | 1.14 GB, **downloads in 34 seconds** |
| Contents of that one file | **94,729,897 positions from 1,479,269 independent games** |
| Read speed | 1,441,184 rows/s single core with pyarrow |
| Schema | `fen`, `cp` / `mate` (White-relative), `move` |

Two files is **~185M positions from ~2.9M independent games** for about 2.3 GB
and five minutes.

**The labels are the part that matters.** Lichess `%eval` comes from fishnet at
**1,000,000 nodes per move**, which is a median depth of **21**. The first
attempt labelled at depth 8, which needs a median of 1,596 nodes. That is
roughly **600x less search per label**, obtained for free.

Measured against Stockfish 19 at depth 18 over 120 positions, the Lichess evals
give Pearson **r = 0.988**: the apparent 108 cp mean error is almost entirely a
cp-scale offset (`SF19_d18 ~ 1.117 x lichess`), and a scale offset is harmless
once the target goes through a sigmoid.

Filters to apply while streaming: drop the 8% mate rows or clamp to +/-10000,
drop `|cp| > 10000`, drop positions where the side to move is in check, and skip
early plies (Stockfish uses `early_fen_skipping 28`, i.e. move 14, which is
41.8% of rows here). Then shuffle, because rows arrive in game order.

**One parameter change from last time, and it is not a small one.**
fishnet-evals carries no game result, so train **pure eval**: bullet `wdl = 0.0`,
nnue-pytorch `lambda = 1.0`. That is deliberately the opposite of the first
attempt. Heavy WDL weighting is correct when labels are shallow and noisy, which
depth-8 self-play labels were. With 1M-node labels this is Stockfish's own
regime, and Stockfish runs `start-lambda 1.0`.

Note the two tools define lambda in opposite directions: nnue-pytorch `lambda`
1.0 means pure eval, bullet `wdl` 1.0 means pure game result.

### Superseded: generating and labelling data ourselves

Kept because the arithmetic is the argument. Measured on this machine, Stockfish
19 single-threaded: **depth 8 = 195 positions/s/thread**, so 100M positions is
**17.8 hours across 8 threads**, and depth 12 is 10 days. That would burn the
entire budget to produce labels 600x shallower than a 34-second download. The
Leela open data is also available but is ODbL share-alike and needs the lc0
rescorer with Syzygy tablebases to become centipawns, which is not a one-day
path.

### Phase 1b: old notes on data sources

Use the **Leela Chess Zero open data**, which is what both Calvin and Serendipity
train on. Calvin used the Kaggle mirror
`linrock/t77dec2021-t78janfeb2022-t80apr2022` and **re-scored it with his own
search** rather than trusting Leela's eval.

Target **100M positions**, sampling 1 to 3 per game. The `Dataset` format, game
ids and the ratio guardrail all already exist to consume it.

The Lichess monthly PGN dumps are the fallback: free, but only a fraction of
games carry `%eval` and the evals are shallower.

### Phase 2: train (hours, not days)

**Keep the Java trainer, and the case for it is now stronger.** This machine is
an **Intel** Mac, so there is no CUDA and no MPS, PyTorch's last macOS x86_64
wheel is torch 2.2.2 against a current 2.14, and bullet has no CPU backend at
all (only CUDA, ROCm and Metal) with a Metal path that has no macOS CI and is
unverified on an Intel iGPU. Every external trainer is a yak shave here.

Meanwhile the machine does **143 GFLOP/s** on Accelerate, and with a sparse
first layer this net costs ~101 kFLOP per position, so a 400M-sample run is
hours. The trainer already exists and is already correct. Calvin abandoned his own Python trainer for bullet on
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
