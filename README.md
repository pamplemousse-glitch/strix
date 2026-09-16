# Strix

A UCI chess engine written from scratch in Java, with its correctness proven by exact
position counts in CI and its strength proven by a public rating on Lichess.

> **Status: spec.** No engine code yet. This README was written before the code, on
> purpose, to force the scope down. Any line below marked `[UNMEASURED]` is a claim that
> has not been produced yet. **The count of those markers must be zero before this repo is
> made public.**

## The problem

Chess engines play by looking ahead: list the legal moves, imagine the opponent's replies,
score the positions that result, pick the move that leads somewhere good.

Four moves deep is already 197,281 positions, and it grows exponentially from there. So the
work is not looking ahead. The work is **looking at far fewer positions without missing the
good moves.**

Two ideas do most of that: stop exploring a move the instant you find one reply that
refutes it (alpha-beta), and remember positions you have already scored, because the same
position is reached by many different move orders (a transposition table).

## Architecture

```
     Chess GUI, or Lichess
              |
              |  "here is the position, pick a move"
              v
        +-----------+
        |    UCI    |   plain text over stdin/stdout
        +-----+-----+
              v
        +-----------+          +------------------+
        |  SEARCH   |<-------->|  TRANSPOSITION   |
        |           |          |      TABLE       |
        | look      |          |  positions       |
        | ahead     |          |  already scored  |
        +--+-----+--+          +------------------+
           |     |
           v     v
    +--------+ +------------+
    | RULES  | | EVALUATION |
    |        | |            |
    | what's | | who is     |
    | legal  | | winning    |
    +--------+ +------------+
              |
              |  "bestmove g1f3"
              v
```

`Rules` and `Evaluation` are plain functions. `Search` calls them millions of times per
second. `UCI` is the door the GUI talks through. Search is the only hard part.

## Two claims, both externally checkable

**Correctness.** The move generator reproduces the published `perft` node counts exactly on
six standard positions, both colors, verified in CI. One wrong rule and the build fails.

```
startpos depth 5   4,865,609
kiwipete depth 4   4,085,603
```

**Pruning, measured.** Nodes to reach depth 6 from the starting position:

| configuration | nodes | speedup |
|---|---|---|
| bare alpha-beta | 3,500,451 | 1.0x |
| + transposition table | 2,384,099 | 1.5x |
| + move ordering | 126,390 | 27.7x |
| + both | 89,109 | 39.3x |

The table alone is worth 1.5x. Move ordering is worth 27.7x. The transposition table's real
contribution is supplying a good first move to try, not its cutoffs.

**What each feature is worth, measured.** Self-play SPRT at fixed 20,000 nodes per
move, exact GSPRT with the pentanomial pair model:

| Feature removed | Elo | Games to settle |
|---|---|---|
| Piece-square tables | +544.7 | 24 |
| Move ordering | +246.6 | 208 |
| Transposition table | +33.5 | 770 |

The games column is the interesting one: the smaller the effect, the more evidence
it takes. A fixed-game harness would have spent the same budget on all three.

These are self-play figures and self-play inflates, since two builds of the same
engine share every blind spot. Roughly 60% typically transfers.

Reproduce any row:

```bash
./gradlew build
java -cp build/classes/java/main strix.harness.Main 4 20000 1200 runs/x.tsv "Ordering=false" 100
```

**Strength.** [UNMEASURED] Will run as a labeled BOT on Lichess.

Neither claim asks you to trust the author.

## Quickstart

```bash
./gradlew test                        # includes the perft suite
./gradlew run                         # speaks UCI on stdin
```

To play against it, point any UCI GUI (Cute Chess, Arena, BanksiaGUI) at the built jar.

## Key design decisions

Each ADR records what else was considered and what the choice cost.

- [0001](docs/adr/0001-bitboards-over-mailbox.md) Bitboards over a mailbox array
- [0003](docs/adr/0003-pseudo-legal-generation.md) Pseudo-legal generation with a legality filter
- [0004](docs/adr/0004-make-unmake-and-packed-moves.md) Make/unmake in place, moves packed into an int
- [0005](docs/adr/0005-alpha-beta-not-pvs.md) Alpha-beta, and why negamax is kept forever
- [0006](docs/adr/0006-quiescence-is-mandatory.md) Quiescence search
- [0007](docs/adr/0007-transposition-table.md) Transposition table
- [0008](docs/adr/0008-move-ordering.md) Move ordering

`docs/devlog.md` has the bugs, including a green build that ran zero tests and a bug
injection that a node-count test happily passed.

## What this is NOT

- **Not competitive with serious engines.** [Calvin](https://github.com/kelseyde/calvin-chess-engine)
  and [Serendipity](https://github.com/xu-shawn/Serendipity) are the strong Java engines.
  This is not trying to be them, and it cites them as references and differential-test oracles.
- **Not an opening book or endgame tablebases.** Those improve results without
  demonstrating anything about the search. They are data, not engineering.
- **Not a parallel search.** Parallel search is nondeterministic, which would break the
  reproducibility every verification step here depends on.
- **Not a GUI.** Speaking UCI means it does not need one.
- **Not a chess variant engine.** Standard chess only.

## Testing

```bash
./gradlew test                        # unit + perft suite
./gradlew perftDeep                   # depth 6, slow, runs nightly not per-commit
```

## License

MIT
