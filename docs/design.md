# Strix: design

## 1. Context and scope

A UCI chess engine written from scratch in Java 25 (the current LTS). It reads a position, searches ahead,
and returns a move. It plays through any UCI GUI and as a labeled BOT account on Lichess.

The engine exists to demonstrate and defend engineering judgment, not to be competitive.
Strong Java engines already exist (Calvin, Serendipity). This is not one of them and does
not claim to be.

All four stages are committed:

| Stage | Content | Done-criteria |
|---|---|---|
| 1 | Move generation, proven by perft | Binary: counts match |
| 2 | Search, evaluation, UCI. It plays. | Binary: legal game in a GUI |
| 3 | Distributed SPRT self-play harness | Open-ended |
| 4 | NNUE evaluation | Open-ended |

**The ship point is still step 12, the end of Stage 2.** Committing to four stages changes
design decisions taken now so that nothing needs a rewrite later. It does not defer
shipping. The repo is public from the first commit and playable at step 12, so an
interrupted job search costs nothing already built.

Stages 3 and 4 have no clear finish line, which is a known property of engine development
past "it plays." That is why they come after a shippable artifact, not before one.

## 2. Goals

1. **Provably correct move generation.** perft matches published counts on six standard
   positions, both colors, in CI.
2. **It plays real games.** Legal games in a real GUI, and rated games on Lichess.
3. **Every optimization proven answer-preserving.** Alpha-beta, the transposition table,
   and move ordering must return the identical best move as the unoptimized search, only
   faster. Any change in the answer is a bug.
4. **Every significant decision recorded at the moment it is made**, with the alternative
   that was declined and what the choice cost.
5. **Every claimed number regenerable** by a command in the repo.

## 3. Non-goals

The sharp part. Each of these could reasonably have been a goal and was deliberately cut.

- **Competitive strength.** Not attempting to approach Calvin or Serendipity. Strength is
  reported, not optimized for.
- **Opening book and endgame tablebases.** They would improve game results while
  demonstrating nothing about the search. They are data, not engineering.
- **Parallel search (Lazy SMP).** Parallel search is nondeterministic, and Goal 3 depends
  on reproducibility. Even with the Stage 3 harness able to measure whether it helps,
  parallelism costs the property that makes every other verification step in this project
  work. Deliberate tradeoff, not an oversight.
- **Chess variants.** Standard chess only. No Chess960, which would require choosing
  between the incompatible X-FEN and Shredder-FEN castling conventions for no benefit here.
- **A GUI.** UCI means one is not needed.
- **chess.com integration.** Their public API is read-only and cannot send moves, and bot
  accounts are not permitted there. Lichess is the supported path.

## 4. The design

Four components. Three are simple; one is not.

```
UCI  ->  SEARCH  ->  RULES
             |   ->  EVALUATION
             +-->  TRANSPOSITION TABLE
```

**Rules.** Bitboard board representation: one 64-bit `long` per piece type and color.
Move generation is pseudo-legal (generate everything, filter moves that leave the king in
check), because it is far easier to prove correct with perft than fully-legal generation.

Sliding-piece attacks are built twice on purpose. **Ray loops first, as the reference
implementation.** Then **magic bitboards as an optimization, which must return identical
perft counts.** Magics use a perfect-hash multiply-and-shift to replace the loop with one
table lookup. Java has no `PEXT` intrinsic, so magics are the ceiling here, and 64-bit
multiplication overflow wraps correctly for `long`, which is what makes the trick work.
Moves are packed into a single `int` (6 bits from, 6 bits to, promotion, flags) rather than
allocated as objects, because a `Move` object per node would push millions of allocations
per second through the GC.

Java note: `long` is signed and there is no unsigned 64-bit type, so bitboard shifts use
`>>>`, not `>>`. Getting this wrong produces a wrong perft count and a long debugging session.

**Evaluation.** Material count first, piece-square tables second, NNUE in Stage 4. Because
Stage 4 is committed, the evaluation interface is **accumulator-shaped from the start**: eval
state is updated incrementally on make/unmake rather than recomputed from the board. A
material-only evaluator does not need this, but writing it this way now means NNUE slots in
without touching make/unmake later.

No evaluation term ships without a way to verify it helps. Until Stage 3 exists, that means
material and piece-square tables only, because an unmeasured eval term is as likely to hurt
as help.

**Search.** Negamax with alpha-beta pruning, iterative deepening, quiescence search at the
leaves, and move ordering (MVV-LVA, killers, history). Alpha-beta's benefit is entirely a
function of move ordering: with good ordering the searched tree is roughly the square root
of the full tree, which is the difference between reaching depth 6 and depth 12.

Iterative deepening looks wasteful and is not: a move is always ready if time runs out, the
shallow searches cost a fraction of the deepest one because the tree grows exponentially,
and the best line from depth N-1 orders moves at depth N, which improves pruning enough to
be a net speedup.

**Transposition table.** Zobrist hashing, 64-bit keys, indexed by the low bits. A partial
key is stored to detect collisions. Collisions do occur and the engine tolerates them.
Replacement policy is an open decision (ADR to be written at that step).

**UCI.** Roughly 200 lines. Read a line, switch on the first word. The GUI owns the game
and resends the full move list every time, so the engine rebuilds the position from scratch
and never has to trust its own state.

## 5. Verification strategy

This is the core of the project and the reason chess was chosen. Every step has an external
oracle, so a plausible-looking bug has nowhere to hide.

| What | Oracle |
|---|---|
| Move generation | Exact published perft counts, plus `perftree` diffing against Stockfish |
| Color symmetry | Flip-board routine: every position tests both White and Black |
| Alpha-beta | Identical best move to plain negamax, fewer nodes |
| Transposition table | Identical best move, fewer nodes. Incremental Zobrist key equals from-scratch key |
| Move ordering | Identical best move, fewer nodes |
| Magic bitboards | Identical perft counts to the ray-loop reference |
| NNUE (Stage 4) | Not answer-preserving. Requires the Stage 3 SPRT harness to justify |
| UCI | Plays a complete legal game in a real GUI |
| Strength | Public Lichess BOT rating |

## 6. Cross-cutting

**Java runtime.** JIT warmup means naive benchmarks lie; any timing number needs warmup
iterations. Allocation in the search loop is the main performance hazard.

**CI.** perft depths 1 through 5 run per-commit (a few seconds). Depth 6 is 119M nodes and
runs nightly.

**Legitimate use.** The engine plays, or Antoine plays. Never both in the same game.
Lichess BOT accounts are publicly labeled and officially supported; using an engine during
one's own live games is cheating on any site.
