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
