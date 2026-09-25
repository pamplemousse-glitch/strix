# ADR 0018: Three search heuristics that lost, and what they had in common

**Date:** 2026-09-25
**Status:** accepted. Null move, aspiration windows and shallow pruning ship OFF.

## What was measured

Six search features, each implemented against a proof gate and then settled by
SPRT at 20,000 nodes per move against the same build with the feature switched
off. Every run audited clean by `strix.tools.LogAudit`.

| Feature | Elo | Verdict | Games | Bounds |
|---|---|---|---|---|
| Transposition table (re-measure) | **+147.2** | H1 | 50 | [0, 100] |
| Late move reductions | **+68.2** | H1 | 98 | [0, 60] |
| Principal variation search | **+26.1** | H1 | 746 | [0, 30] |
| Null move pruning | **+2.0** | drifting to H0 | 2,066 | [0, 10] |
| Futility + late move pruning | **-19.2** | H0 | 362 | [0, 20] |
| Aspiration windows | **-33.9** | H0 | 154 | [0, 25] |

Three of six do not work in this engine. All three ship off, behind flags.

## The pattern, which is the point of this ADR

The three that worked and the three that did not split cleanly, and not by
difficulty or by how standard they are.

**What LMR and PVS depend on: move ordering.** Both bet that the first move is
usually best. Neither asks the evaluation function a question. Ordering here is
MVV-LVA plus killers plus history, and it is good, so both bets pay.

**What the three failures depend on: the evaluation.**

- **Futility** prunes a quiet move when the static score plus a margin does not
  reach alpha. That is a direct bet that the static score predicts what a search
  would find.
- **Null move** bets that a reduced search after forfeiting a turn produces a
  score meaningful enough to cut on. The reduced search bottoms out in the same
  evaluation.
- **Aspiration** bets that this iteration's score will land near the last one's.
  That is a bet on the evaluation being *stable* across depth.

This engine evaluates with piece-square tables, and its one attempt to tune them
was measured at -57.6 Elo and rejected (ADR 0013). So the common factor in all
three failures is an evaluation that is not good enough to prune on, predict
from, or extrapolate.

## Why this matters more than the Elo

`rebuild-plan.md` ordered search before evaluation, and the argument was PeSTO:
a piece-square-table engine rated 2988 CCRL, therefore the eval class is not the
limit and the search is. That argument survives, but it is now incomplete.

**Search techniques are not eval-independent.** Roughly half the standard search
toolkit is eval-dependent, and this measurement says that half is unavailable
until the evaluation improves. The remaining search work worth doing here is the
ordering-dependent kind.

It also reframes the NNUE question. The case for NNUE was its own Elo. The case
is now larger: a better evaluation is what unlocks null move, futility and
aspiration, each of which is worth doing and none of which currently is.

## What was not concluded

That the implementations are wrong. Each passed a gate designed to catch the
specific way it fails silently:

- PVS returns the same score as plain alpha-beta and the same score as unpruned
  negamax, so it is not returning a null-window bound as if it were a value.
- Null move restores every byte of state including the Zobrist key, across
  nested nulls, and en passant expires while the fifty-move clock does not.
- Aspiration returns the same score as a full window without a transposition
  table and the same move with one.
- Shallow pruning never prunes every move at a node, which would return
  -INFINITY and report a loss that does not exist.

A bug is still possible. But "it is implemented correctly and does not pay here"
is what the evidence supports, and it is a different conclusion with a different
next step.

## What it cost

Two of the three are a few dozen lines each and stay behind flags, because the
reason they fail is specific and may not survive a better evaluation. Re-measure
them after any evaluation change rather than assuming either outcome.

Writing the aspiration proof gate also found a real bug in unrelated code: a
fallback time budget was being applied to depth-limited searches, so the same
depth-4 position returned 945 on one run and 895 on the next. Every
reproducibility test in this repo depends on that not happening, and nothing
else would have caught it.
