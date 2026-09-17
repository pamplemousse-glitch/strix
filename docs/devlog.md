# Dev log

Running notes. The point of this file is that "tell me about a hard bug" has a real
answer in it, written while the details were fresh.

## 2026-09-15: Stage 1

**Move generation passed perft on the first run.** All six standard positions,
depths up to 6, roughly 760M nodes. This is not the normal outcome and I did not
trust it, so it was verified three more ways.

**The one real bug was in the build, not the engine.** The `perftDeep` Gradle task
reported `BUILD SUCCESSFUL` in 1 second having run **zero tests**. A registered
`Test` task does not inherit the test source set; it needs `testClassesDirs` and
`classpath` set explicitly. A green build that proves nothing is the exact failure
mode this project exists to prevent, and it arrived through the build config,
which is the part nobody reads.

**Bug injection, to prove the suite can fail.** Removed `CASTLE_MASK[to]` from the
castling-rights update, which breaks the "enemy rook captured on its home square"
case.

| Position | Result |
|---|---|
| Kiwipete | FAILED |
| Position 5 | FAILED |
| Flip-board symmetry | FAILED |
| Positions 1, 3, 4, 6 | still passed |

Exactly the right four. Position 6 has no castling rights at all, so it could not
possibly have noticed. Reverting restored all eight tests.

**Independent cross-check against Stockfish 19.** `Perft.divide()` output diffed
against `go perft N` for all six positions. Every root move's subtree count
identical: 20, 48, 14, 6, 44, and 46 root moves respectively. This is the check
that matters, because until it ran, perft only agreed with numbers transcribed
from a web page into a test file.

**Throughput:** roughly 10M nodes/sec with ray-loop sliding attacks. That is the
baseline magic bitboards have to beat in step 7b, and the number they must
reproduce exactly.

## 2026-09-15: Stage 2

**Alpha-beta pruning, measured.** Negamax is kept permanently as the reference
answer. Node counts at depth 4:

| position | negamax | alpha-beta | kept |
|---|---|---|---|
| startpos | 206,604 | 2,036 | 1.0% |
| kiwipete | 4,185,553 | 28,197 | 0.7% |
| midgame | 3,986,610 | 124,632 | 3.1% |

Kiwipete is a 148x reduction for an identical answer.

**Bug injection on alpha-beta.** Passed the recursive window as `(-alpha, -beta)`
instead of `(-beta, -alpha)`, a one-token swap.

| test | result |
|---|---|
| all 6 perft positions | passed, correctly (move generation untouched) |
| same score as negamax | FAILED |
| finds mate in one | FAILED |
| prefers winning material | FAILED |
| "pruning saves a lot at depth 4" | **passed** |

That last row is the lesson. **A completely broken search still passed the
node-count test**, because it pruned plenty, just the wrong branches. Node counts
prove a search is fast. Only the comparison against unpruned negamax proves it is
right. Writing only the speed test ships this bug.

**The horizon effect, observed live.** After 1.e4 e5 the engine reported +100 at
depth 3 playing Nf3, then -100 at depth 4. Alpha-beta and negamax agreed at every
depth, so the search was provably correct and this was not a bug: at depth 3 it
sees Nxe5 winning a pawn and the recapture falls one ply past the horizon. The
invariant test is what made that a thirty-second diagnosis instead of a hunt.

Quiescence search fixed it. The score is now a flat 0 at every depth from that
position.

**Material-only eval cannot play chess.** With material alone every quiet move
scores identically, so the engine played whatever was generated first: a2a3, every
time, at every depth. Adding piece-square tables changed it to Nc3 and d4. This is
why "material only" was never the destination.

## 2026-09-16: Stage 3 begins

**Draw detection was missing entirely.** Before any match can be run, something has
to decide when a game is over, and Strix could only detect checkmate and stalemate.
No threefold repetition, no fifty-move rule, no insufficient material. Two engines
would shuffle forever and no match would ever terminate. It was also a real playing
bug: the engine would happily repeat a position while winning.

Three of the five new tests failed on the first run, and **all three were my test
positions, not the engine**:

- The "stalemate" FEN wasn't stalemate. The white king could still run to b1.
- The repetition test shuffled rooks from their **home squares**, so the very first
  move permanently destroyed a castling right. The position after the cycle was not
  the same position, and the Zobrist hash correctly said so.

That second one is the same lesson as the en passant case from Stage 2: **castling
rights and en passant squares are part of a position's identity**, not decoration
on top of piece placement. Learned it twice from opposite directions.

**The SPRT bug, which is the one worth remembering.**

The class comment I wrote said porting statistics you did not derive is exactly the
situation that needs an external check. It then immediately proved itself.

A +40 Elo patch was being REJECTED after 2 pairs, with an LLR of **-3,649,310,055**.

Cause: the normal-approximation GSPRT divides by the observed variance of the pair
score. With two samples that variance can be exactly zero (both pairs scoring the
same), and I had clamped it to `1e-12` rather than treating it as insufficient
information. Dividing by 1e-12 produces billions, which crosses a bound of 2.94
instantly.

The failure mode matters more than the fix. This would not have looked like a bug.
It produces a clean number, a confident verdict, and a fast answer. **Every
measurement in Stage 3 and Stage 4 would have been authoritative-looking garbage**,
and NNUE would have been tuned against noise.

Fix: a minimum of 16 pairs before any verdict, and zero observed variance returns
an LLR of 0 (keep playing) instead of infinity.

Caught by the synthetic test: simulate a player of known strength, check the test
reaches the right verdict at the right rate. Verified across four cases, including
that a genuinely worthless patch is rejected and the false-accept rate stays near
the alpha of 0.05.

## 2026-09-16: the first Elo measurements

**A silent no-op edit.** A scripted edit to `UciEngine` searched for a string that
did not exist in the file, replaced nothing, and printed "success" anyway, because
the confirmation was unconditional. The next compile passed because nothing had
changed. Caught by grepping for the symbol that should have appeared. Edits now
assert that the content actually changed.

**Fixed nodes is reproducible, and that was checked rather than assumed.**
`go nodes 200000` from the same position returned the identical move three runs out
of three. With move ordering disabled the engine reaches depth 3 in 20,000 nodes;
with it on, depth 5. Two extra plies for the same work.

**The exact GSPRT exposed a different small-sample failure.** Swapping the normal
approximation for the exact form removed the divide-by-zero-variance bug, then
produced its own: an engine losing at **-88.7 Elo** returned an LLR of **+15.71**
after two pairs. With two pairs the observed mass sits in one or two buckets and
there may be no distribution on that support with the hypothesised mean, so the
root find runs to the edge of its bracket and returns nonsense.

Fixed with a Jeffreys prior of 0.5 pseudo-counts per bucket. The generalisable
lesson is that **small samples were the hazard in both formulations**, and the
synthetic known-strength test is the only thing that found either one.

**The bounds mistake, in both directions.** See ADR 0012. Measuring a +240 Elo
ablation with Fishtest's `[0, 5]` bounds produced an LLR of +0.92 after 71 pairs;
the same data under `[0, 100]` gave +23.01. And the transposition table returned
H0_ACCEPTED at -29 Elo after 48 games, which means "not worth 100 Elo", not
"worthless". The second is the dangerous one: a fast, confident, misreadable
rejection that invites deleting a feature that was helping.

**Resume worked.** Killing the runner mid-match and restarting picked up 100 pairs
from the durable log, needed 4 more, and settled in 27 seconds.

### First measured results, self-play, fixed 20k nodes

| Feature removed | Elo | Games to settle | Bounds |
|---|---|---|---|
| Piece-square tables | **+544.7** | 24 | [0, 100] |
| Move ordering | **+246.6** | 208 | [0, 100] |
| Transposition table | **+33.5** | 770 | [0, 15] |

**The games column is the point.** 24, then 208, then 770: the smaller the effect,
the more evidence it takes to prove. That is SPRT spending compute in proportion to
how hard the question is. A fixed-game-count harness would have burned an identical
budget on all three and learned less.

The transposition table is also the one that behaved well under uncertainty. At the
600-pair cap it reported **LLR +1.86 of the 2.94 needed, verdict CONTINUE**: a clear
positive trend, honestly labelled as not yet proven. Resuming from the durable log
took it to +3.01 and acceptance. Both earlier broken versions of this code would
have returned a confident number there instead.

**Cross-validation against Stage 2.** The pure node-count measurements taken days
earlier ranked these features identically: move ordering cut the tree 27.7x while
the transposition table alone managed 1.5x. Two independent methods, same ordering.

These are **self-play** numbers. Rustic's documentation reports roughly 60% of
self-play gains transfer to play against other engines, so treat them as upper
bounds.

## 2026-09-16: Texel tuning, and the error going down while the engine got worse

**Magic bitboards first.** +31% nodes per second, node counts byte-identical. The
test checks 256,000 random **full-board** occupancies, not subsets of the relevant
mask: the tables are built from mask subsets, so testing only those would be
circular and would never catch a wrong mask. ADR 0002.

**Then the tuner shipped a worse evaluation and said it was better.**

First full run: 198,014 positions, 12 passes, training error down 5.1% from
0.08898 to 0.08440. Every number said it worked.

The tuned knight table:

```
-114,   32,  -54,   66,   18,  -14,  -96, -130,
 -72,   68,   40,  -16,  -64,   72,    4,   -8,
  66,   96,  -62,   71,   -9,    2,   88,  -54,
```

That is not chess. +96 on b6 sits beside -62 on c6. The pawn table scored a pawn
on g7, one square from promoting, at -14.

**Only the readable output caught it.** Emitting pasteable Java rather than a
binary blob was a throwaway decision made for convenience, and it is the sole
reason this was visible. A blob would have produced the identical numbers and
shipped.

**The cause is that positions inside a game are not independent.** 198,014
positions came from 6,000 games, so ~33 positions share each result label, each
pawn structure, each set of pieces. The effective sample size is nearer 6,000 than
198,000, which is about 13 independent samples for each of 453 parameters.
Coordinate descent will cheerfully spend a dozen passes fitting individual squares
to the quirks of individual games.

**The fix is a holdout, split by GAME rather than by position.** Splitting by
position would scatter near-duplicates across both sets and report no overfitting
at all.

It found the turn immediately:

| pass | train | validate | |
|---|---|---|---|
| 1 | 0.08706 | 0.08823 | both improving |
| 2 | 0.08606 | **0.08845** | **training better, held-out worse** |

Overfitting starts at pass two. The first run did twelve.

Stopping at pass one produces a knight table that is a clean symmetric bowl,
corners at -58, centre at +23, and material of 100/312/322/492/908.

**The lesson is the same one this project keeps relearning in new costumes:** a
metric improving is not the thing you wanted improving. Alpha-beta pruned plenty
while returning garbage. The node-count test passed on a broken search. Training
error fell while the evaluation degraded. Every time, the only thing that caught it
was a second, independent measurement that the optimisation could not influence.
