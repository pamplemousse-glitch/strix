# Strix: build plan

Fifteen steps. Each has a proof gate. **Nothing moves to the next step until the current
gate passes.** One step, one commit, one understanding-gate check.

Per `CLAUDE.md` rule 2, the test is written and observed failing before the implementation
exists.

## Stage 1: Rules

| # | Build | Proof gate | ADR | Author |
|---|---|---|---|---|
| 1 | Board, FEN parse and print | 20 FEN strings round-trip unchanged; printed diagram matches by eye | 0001 bitboards over mailbox | AI + review |
| 2 | Knight and king moves | perft depth 1 and 2 on simple positions | | AI + review |
| 3 | Pawns: double push, en passant, promotion | perft. Most bugs live here | | AI + review |
| 4 | Sliding pieces | perft | 0002 ray loops over magic bitboards | AI + review |
| 5 | Castling, plus filtering king-in-check | perft on Kiwipete, designed to break exactly this | 0003 pseudo-legal over fully-legal | **Antoine hand-writes the generation loop** |
| 6 | Make / unmake | perft again. Undo bugs surface as wrong counts | 0004 make-unmake over copy-make; move packed into int | AI + review |
| 7 | Full suite + flip-board | All 6 positions, both colors, exact match. CI green | | AI + review |
| 7b | **Magic bitboards** (optimization) | **Identical perft counts to step 4's ray loops**, measurably faster | 0002 magics over ray loops; why no PEXT in Java | AI + review |

## Stage 2: Search

| # | Build | Proof gate | ADR | Author |
|---|---|---|---|---|
| 8 | Material-only evaluation | Prefers winning a queen over losing one | | AI + review |
| 9 | Negamax, no pruning | Finds mate in 1, then mate in 2, on known positions | | AI + review |
| 10 | Alpha-beta pruning | **Same best move as step 9, far fewer nodes** | 0005 alpha-beta; what PVS would add | **Antoine hand-writes alpha-beta** |
| 11 | Iterative deepening | Returns a legal move at any cutoff, including 10ms | | AI + review |
| 12 | UCI | Loads in Cute Chess, plays a complete legal game | | AI + review |
| 13 | Quiescence search | Stops hanging pieces on known tactical positions | 0006 why quiescence is mandatory | AI + review |
| 14 | Zobrist + transposition table | Same best move, fewer nodes. **Incremental key == from-scratch key** | 0007 TT size, replacement, why 64-bit | AI + review |
| 15 | Move ordering (MVV-LVA, killers, history) | Same best move, fewer nodes. Record before/after counts | 0008 ordering scheme + measured node reduction | AI + review |

**Ship point is step 12.** Steps 13 onward are strength and speed on top of a working,
public, playable engine. All four stages are committed, but the artifact exists at step 12,
so an interrupted job search costs nothing already built.

## Stage 3: Measurement

| # | Build | Proof gate | ADR |
|---|---|---|---|
| 16 | Self-play match runner, engine vs engine | Runs N games unattended, resumable after a kill | 0009 job model and idempotent keys |
| 17 | SPRT stopping rule | Reproduces a known Elo delta on a deliberately weakened build | 0010 SPRT bounds and batch sizing |
| 18 | Parallel workers | Same verdict as single-worker, wall-clock lower | 0011 straggler handling |
| 18b | Texel tuning of eval parameters | Measured Elo gain vs untuned, confirmed by SPRT | 0011b dataset and objective |
| 18c | SPSA tuning of search parameters | Measured Elo gain vs untuned, confirmed by SPRT | 0011c which parameters, perturbation size |

### The batching decision (ADR 0010)

SPRT is sequential, workers are not, so when the test crosses its bound some games are
still running. **This is not an open question: the pentanomial model settles it.**

Games are played in **pairs** (same opening, colors reversed) and the model scores the pair
jointly as one of five outcomes. **The pair, not the game, is the atomic observation.** Half
a pair is not an observation the model can consume. So games are batched, batch sizes are
even, and an incomplete batch is discarded. Fishtest does exactly this: workers report every
8 games, and quitting mid-batch loses those games.

The real decision is **batch size**, which trades stopping resolution and network chatter
against games wasted at the boundary. That is what ADR 0010 records.

### Three tuning tools, three different jobs

Do not confuse these. They answer different questions.

| Question | Tool | Why |
|---|---|---|
| "Does this feature help at all?" | **SPRT** | Discrete yes/no on one change |
| "What should these eval numbers be?" | **Texel tuning** | Fits hundreds of parameters at once against a static dataset of positions labeled with game outcomes. No games needed, so it is fast |
| "What should these search thresholds be?" | **SPSA** | Search parameters have no static objective, so they must be measured by playing. Plays perturbed parameter sets against each other to estimate a gradient. Stockfish's method since 2011 |

Texel tuning of piece-square tables is high value and cheap: Rustic measured roughly +270 to
+300 Elo from tapered, tuned evaluation. Worth doing even though Stage 4's network will
eventually subsume it, because a decent eval is also what generates usable self-play data
for training that network.

## Stage 4: NNUE

| # | Build | Proof gate | ADR |
|---|---|---|---|
| 19 | Network inference, float | Matches a reference forward pass on known inputs | 0012 architecture, trainer, data |
| 20 | Incremental accumulator | **Incremental eval equals from-scratch eval** on every position | 0013 |
| 21 | Integer quantization | Quantized output within tolerance of float | 0014 quantization scheme |
| 22 | Training and integration | **SPRT pass vs the piece-square-table build** | 0015 |

Step 20's gate is the same shape as Zobrist's in step 14: an incrementally maintained value
must equal the from-scratch computation, always. Step 22 is the one gate in this project
that is not answer-preserving, which is exactly why Stage 3 has to exist first.

Java note: the Vector API is still an incubator module in 25, so the SIMD story differs from
C++ engines. That constraint is real and goes in ADR 0014.

### Stage 4 decisions (ADR 0012)

**Architecture.** Start with a **perspective network, `768 -> Nx2 -> 1`**. 768 inputs is
6 piece types x 2 colors x 64 squares. "Perspective" means the same inputs are fed twice,
once labeled from the side-to-move's view and once from the opponent's, with the first-layer
weights shared and the two outputs concatenated. Hidden size N in the 256 to 1024 range.

The harder alternative is king-bucketed inputs (HalfKP / HalfKA), which is what Stockfish
uses. Far larger input space, more data required. Not the right first network.

**Trainer.** Use [`bullet`](https://github.com/jw1912/bullet) (Rust, jw1912), the most widely
adopted trainer among top engines and what Serendipity used. The alternative is Stockfish's
`nnue-pytorch`, or writing a trainer from scratch.

**Writing the trainer is the wrong place to spend effort here.** The defensible artifact is
the **inference** side: a quantized forward pass with an incrementally updated accumulator,
hand-written in Java. That is the "AI fundamentally, not API calls" story. Training is
running someone else's tool on a GPU.

**Data.** Self-play positions from Strix once it is strong enough, or Leela Chess Zero's
open data (what Serendipity trained on), or Stockfish-labeled positions.

## The invariant that matters most

Steps 10, 14, and 15 share one proof gate:

> The best move must not change. Only the node count may drop.

That is not a coincidence, it is the definition. Alpha-beta, transposition tables, and move
ordering are **optimizations, not improvements**. They must find the identical answer faster.
If alpha-beta returns a different move than plain negamax, there is a bug, full stop.

Most people never write that test and therefore never really understand what alpha-beta
does. Writing it makes understanding unavoidable.

## Reference perft numbers

**These must be verified against https://www.chessprogramming.org/Perft_Results before the
test is committed.** Per `CLAUDE.md` rule 7, the assistant's recall of these is not
sufficient, and they are the oracle for the entire project.

```
Position 1, startpos
  rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
  d1 20  d2 400  d3 8902  d4 197281  d5 4865609  d6 119060324

Position 2, "Kiwipete"
  r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1
  d1 48  d2 2039  d3 97862  d4 4085603  d5 193690690

Position 3
  8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1
  d1 14  d2 191  d3 2812  d4 43238  d5 674624  d6 11030083

Position 4
  r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1
  d1 6  d2 264  d3 9467  d4 422333  d5 15833292

Position 5
  rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8
  d1 44  d2 1486  d3 62379  d4 2103487  d5 89941194

Position 6
  r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10
  d1 46  d2 2079  d3 89890  d4 3894594  d5 164075551
```

## Debugging technique for steps 3 to 6

When a perft count is wrong, do not read code looking for the bug.

1. Run **perft divide**: print the node count per root move instead of one total.
2. Diff against Stockfish's `go perft N` for the same position. Exactly one move disagrees.
3. Make that move and repeat at depth-1.
4. Descend until you are looking at the position where the counts first diverge. The bug
   is visible there.

This turns an unbounded bug hunt into a binary search. [`perftree`](https://github.com/agausmann/perftree)
automates the diff against Stockfish.

**Keep the log of these sessions.** "My depth-5 count was off by 15 and perftree localized
it to a rook capture on h8 that should have cleared Black's kingside castling right" is a
far better answer to "tell me about a hard bug" than anything invented under pressure.

## Tooling, installed before step 1

```bash
brew install --cask temurin@25
brew install stockfish            # perft oracle, opponent, evaluator
brew install --cask cutechess     # GUI and automated matches
# perftree: cargo install perftree, or clone agausmann/perftree
```

## Why sliding pieces get built twice

Ray loops are written first and kept. Magic bitboards replace them in step 7b and **must
return byte-identical perft counts**. Same discipline as negamax to alpha-beta: an
optimization that changes the answer is a bug, and keeping the slow reference is what lets
you prove it didn't.

This is strictly better than writing magics directly. It costs about half a day and buys a
correctness proof for the hottest function in the engine.
