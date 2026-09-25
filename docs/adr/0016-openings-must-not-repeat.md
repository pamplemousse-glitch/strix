# ADR 0016: A replayed game is not a second observation

**Date:** 2026-09-24
**Status:** accepted. Supersedes the confidence, though not the point estimates,
of every row in the README's feature table except piece-square tables.

## What was wrong

`MatchRunner` built jobs as `new PairJob(p % Openings.size(), p)` against a
48-line book. Games run at a fixed node count, `Search` has no RNG and no
wall-clock dependence under a node budget, and each game starts with `ucinewgame`.

So pair 0 and pair 48 were not similar games. They were the same game, move for
move.

Visible in every log in `runs/`:

| log | pairs recorded | distinct (opening, bucket, result) |
|---|---|---|
| `hash-tight.tsv` | 385 | **48** |
| `texel-sprt.tsv` | 280 | **48** |
| `ordering.tsv` | 104 | **48** |
| `nnue-sprt.tsv` | 52 | **48** |
| `eval-material.tsv` | 12 | 12 |

## Why it is worse than a lost constant factor

`Gsprt.llr` is linear in the bucket counts. Scaling every count by k leaves
`phat`, `p0` and `p1` untouched and multiplies the sum by k. Replication
therefore multiplies the LLR while adding no information at all.

The consequence is not "less evidence than claimed". It is that **past 48 pairs
the test crosses a bound with probability approaching 1**, in whichever direction
those 48 games happen to lean. Alpha and beta are void beyond the book size, not
merely loosened. The book size was a hard ceiling on obtainable evidence and
nothing anywhere said so.

Reconstructed on the shipped data, `hash-tight.tsv` at `[0, 15]`:

| | pairs | LLR | verdict |
|---|---|---|---|
| as run | 384 | +2.96 | H1_ACCEPTED (bound 2.94) |
| distinct games only | 48 | **+0.36** | CONTINUE |

That is the README's "transposition table, +33.5 Elo, 770 games". The point
estimate is a mean score and replication does not move a mean, so +33.5 stands.
The claim that it was settled does not.

## What `SprtTest` could not have caught

It drives `Sprt` directly with a fresh RNG per pair and never touches
`MatchRunner`, `Openings` or `Game`. The entire path that produced every Elo
number in this repo is outside it, and this defect is invisible to it by
construction. The synthetic known-strength test found two real bugs in the
statistics (ADR 0010) and could never have found this one, because the
statistics were never wrong: the input was.

## The fix

`Openings.lineFor(pairIndex)` extends the book line past the first cycle by two
or four uniformly random legal plies, seeded from the pair index.

Seeded from the pair index is the load-bearing part. Two workers handed the same
pair still produce identical games, which is precisely what ADR 0011 relies on
and what lets the coordinator drop a duplicate result rather than reconcile it.

Random plies unbalance the position. That is acceptable here for the same reason
pairs exist: both engines play the line from both colours, so the imbalance lands
on each exactly once and cancels in the pair score. It is capped at four plies
because a wildly lost starting position measures how well both engines convert a
win rather than which of them is stronger.

## What was considered instead

**A bigger book.** Better, and not mutually exclusive with this, but it only
moves the ceiling: 500 openings still caps a run at 500 pairs, and this project
has already simulated needing 34,000.

**Non-deterministic search.** Would destroy the reproducibility every other
verification step here depends on, including the cluster's duplicate dropping.

**Capping `maxPairs` at the book size.** Honest, and would have been the right
one-line emergency fix, but it caps the transposition-table measurement at an LLR
of 0.36 forever.

## What this costs

Every feature measurement in the README has to be re-run to claim its verdict
again. They are kept with the correction attached rather than deleted, because
the mistake is the useful part: this repo spends two ADRs arguing that
independent samples are games and not positions, and its own harness was counting
one game eight times.
