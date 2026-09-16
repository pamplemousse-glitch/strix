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
