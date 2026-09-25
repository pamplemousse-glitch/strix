# Strix: rebuild plan

*Written 2026-09-25. Continues `build-plan.md`, which ended at step 18c.*

Same contract as the original: **one step, one proof gate, nothing moves until the
gate passes.** Every strength claim is settled by SPRT, and per ADR 0012 the
bounds are chosen from the expected effect size **before** the run starts, not
after seeing the result.

## Where the project actually is

| | |
|---|---|
| Lichess blitz | **1695, settled**, 54 rated games (53W/9L) |
| Correctness | perft exact on 6 positions, both colours, in CI |
| Every self-play Elo figure | **unsupported** until re-measured. See ADR 0016 |
| External calibration | none |
| Evaluation | piece-square tables. Two learned evals measured and rejected |

**The governing fact:** PeSTO evaluates with piece-square tables and nothing else
and is rated 2988 CCRL Blitz. Strix has piece-square tables and is at ~1695.
**The gap is search, not evaluation**, and it is roughly 1300 Elo wide.

Ordering follows from that, and from one operational constraint: the Lichess
rating is the only externally verifiable claim the project has, so work that
risks it waits behind work that does not.

---

## Stage 5: Restore the claims

The harness replayed 48 openings and counted each replay as new evidence
(ADR 0016). The fix shipped; the measurements did not. Until this stage
completes, the README's feature table is a correction notice rather than a
result.

| # | Build | Proof gate | Bounds |
|---|---|---|---|
| 19 | Re-measure piece-square tables | Settles, and the distinct-pair count equals the recorded pair count | `[0, 100]` |
| 20 | Re-measure move ordering | Same | `[0, 100]` |
| 21 | Re-measure the transposition table | Same. Expect this one to move most: LLR reconstructed at +0.36 over distinct pairs against +2.96 as run | `[0, 15]` |
| 22 | Re-measure quiescence | Same | `[0, 100]` |
| 23 | README carries measured numbers again | Every row cites a run log whose distinct-pair count matches its recorded count | |

**Gate for the whole stage:** a script that reads any run log and reports
`recorded pairs / distinct (opening, bucket, result) triples`. A ratio above 1.0
is a replayed sample and fails the gate.

## Stage 6: Calibrate against something that is not us

Self-play inflates, and the published rule of thumb is that roughly 60% of a
self-play gain survives contact with a different opponent. The project has never
measured against an outside engine at a fixed strength.

| # | Build | Proof gate | ADR |
|---|---|---|---|
| 24 | Fixed-games harness mode against an external UCI binary | 1000 games vs a known opponent, unattended and resumable | |
| 25 | Measure against the Stash ladder (v11 = 1690, v14 = 2060) | A rating estimate with an error bar, independent of Lichess and of self-play | 0018 |

**Why a ladder and not more SPRT:** SPRT answers "is A better than B". This stage
asks "how strong is Strix", which is a different question needing a fixed sample
against a known reference.

## Stage 7: Search

The measured ablation, from Delorme's Dumb engine, of removing one feature:

| Feature | Elo | Present? |
|---|---|---|
| MVV-LVA | -495 | yes |
| Transposition table | -283 | yes |
| **Late move reductions** | **-229** | **no** |
| Quiescence | -145 | yes |
| **Null move** | **-116** | **no** |
| **Aspiration windows** | **-101** | **no** |
| History | -94 | yes |
| **Late move pruning** | **-38** | **no** |

| # | Build | Proof gate | Bounds | ADR |
|---|---|---|---|---|
| 26 | Principal variation search | Same best move as plain alpha-beta at fixed depth, fewer nodes | `[0, 30]` | 0019 |
| 27 | Null move pruning | Zugzwang guard verified on known positions; SPRT positive | `[0, 50]` | 0020 |
| 28 | Aspiration windows | Re-search on fail high/low is correct; SPRT positive | `[0, 50]` | 0021 |
| 29 | Late move reductions | SPRT positive. The largest single item on the list | `[0, 100]` | 0022 |
| 30 | Late move pruning and futility | SPRT positive | `[0, 20]` | 0023 |
| 31 | Static exchange evaluation | Correct on hand-checked exchanges; used in ordering and qsearch pruning | `[0, 30]` | 0024 |

**Order matters and is not arbitrary.** PVS first because LMR's re-search
depends on a null-window search being correct. Null move before LMR because both
reduce, and attributing a regression to one of two simultaneous reducers is not
possible. Aspiration before LMR because LMR's effect size is what aspiration's
bounds get sized against.

**Gate discipline:** each step is its own branch, its own SPRT, its own ADR, and
is reverted rather than kept if the test says H0. A feature that cannot be
measured positive does not ship because the textbook says it should work.

## Stage 8: Evaluation

Detail lives in `nnue-redo-plan.md`. Summarised here so the sequence is in one
place.

| # | Build | Proof gate | Bounds |
|---|---|---|---|
| 32 | Dead-unit instrumentation | Per-epoch histogram of pre-activations; the metric that would have ended the first attempt in minutes | |
| 33 | Real data | ~185M positions from ~2.9M independent games via `Lichess/fishnet-evals`. Ratio guardrail passes without `--force` | |
| 34 | Train | `--curve` first. A falling curve at 100% justifies more data; a flat one means data is not the constraint | |
| 35 | Verdict | SPRT vs piece-square tables. Ship or write ADR 0025 | `[0, 100]` |

**Why this is stage 8 and not stage 5.** An NNUE tested at 1695 produces a large
self-play number whether or not the net is good, masking both the net's defects
and the search bugs beneath it. Documented first-NNUE results include Bagatur at
-80 Elo and NoaChess at +4.5 +/- 11.4. The already-written inference converts far
better from 2900 than from 1695.

## Stage 9: Operations

| # | Build | Proof gate |
|---|---|---|
| 36 | Bot and compute do not contend | The bot is stopped during any fixed-clock measurement, automatically or by documented procedure. A rating collected under CPU contention is a rating for a slower engine |
| 37 | Benchmark discipline | `bench` discards its first two runs. The JIT makes run one meaningless; observed 96k nps on run 1 against 270k on run 2 |
| 38 | Nightly regression | perft deep, the full suite, and an A/A SPRT that must return H0 |

---

## What "done" looks like

- Every number in the README cites a run whose distinct-pair count equals its
  recorded count.
- A strength estimate exists that depends on neither self-play nor Lichess.
- Search carries the features the ablation table says are worth ~600 Elo, each
  one individually measured rather than assumed.
- The evaluation question is answered with evidence either way.

## What is deliberately not in scope

- **Parallel search.** Nondeterministic, and every verification step here depends
  on reproducibility. `strix.cluster` distributes whole games instead.
- **Opening books and tablebases.** They improve results without demonstrating
  anything about the search. Data, not engineering.
- **The Vector API.** Only matters once an NNUE is actually shipping, and one
  published Java attempt found it slower than scalar. Stage 8 or later.
