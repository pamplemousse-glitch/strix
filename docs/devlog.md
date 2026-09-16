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
