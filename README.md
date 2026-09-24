# Strix

[![CI](https://github.com/pamplemousse-glitch/strix/actions/workflows/ci.yml/badge.svg)](https://github.com/pamplemousse-glitch/strix/actions/workflows/ci.yml)

A UCI chess engine written from scratch in Java, with its correctness proven by exact
position counts in CI and every strength claim settled by several hundred games of
self-play rather than by opinion.

This README was written before the code, to force the scope down. Every number below is
reproducible with a command in this repo.

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

## Claims, all externally checkable

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

| Feature removed | Elo | Games played | Games that were *distinct* |
|---|---|---|---|
| Piece-square tables | +544.7 | 24 | 24 |
| Move ordering | +246.6 | 208 | 96 |
| Transposition table | +33.5 | 770 | 96 |
| Magic bitboards | +31% nps, 0 Elo by design | n/a | n/a |
| **Texel tuning** | **-57.6, rejected** | 560 | 96 |
| **NNUE** | **-330.5, rejected** | 104 | 96 |

**The fourth column is a correction, and it matters more than the third.**

The harness drew openings as `pairIndex % 48` from a 48-line book, and the search is
deterministic at a fixed node count. So pair 0 and pair 48 were not similar games,
they were the *same* game, move for move. `runs/texel-sprt.tsv` holds 280 pairs and
48 distinct (opening, bucket, result) triples. `runs/hash-tight.tsv` holds 385 and 48.

The LLR is linear in the bucket counts, so replaying a sample k times multiplies it by
k while adding no information. Past the book size the test crosses a bound with
probability approaching 1, in whichever direction those 48 games happen to lean, and
the alpha and beta guarantees are void rather than merely weakened.

**What survives and what does not.** Elo here is a mean-score estimate, and
replication does not move a mean, so the figures in column two stand. The
*confidence* does not. The transposition-table row drops from LLR +2.96, past the
2.94 bound, to roughly +0.36 over the 48 distinct pairs: "keep playing", not "+33.5
Elo, settled". Only the piece-square-table row, which finished inside the book at 12
pairs, is untouched.

Fixed in `Openings.lineFor`: past the first cycle the book line is extended by 2, 4 or
6 random legal plies seeded from the pair index. Still exactly reproducible per pair,
which is what ADR 0011 and the cluster's duplicate dropping both depend on, but pair 0
and pair 48 are now different games. Colours swap inside a pair, so the imbalance a
random ply introduces lands on each engine once and cancels.

These rows have not yet been re-measured under the fix. They are kept with the
correction attached rather than quietly deleted, because the mistake is the
interesting part: this repo argues at length that independent samples are games and
not positions, and its own harness was counting one game up to eight times.

The games column is the interesting one: the smaller the effect, the more evidence
it takes. A fixed-game harness would have spent the same budget on all of them.

**The two rejected rows are the useful ones.** In both cases every proxy metric improved
and the engine got worse, and the only thing that disagreed was several hundred games of
chess. Neither change is shipped.

[ADR 0013](docs/adr/0013-texel-tuning-rejected.md) covers Texel tuning.
[ADR 0014](docs/adr/0014-nnue-rejected.md) covers NNUE, including a real bug found along
the way (254 of 256 hidden units dead, because the accumulator spanned [-15, 9] against a
clipped ReLU window of [0, 1]) and the more interesting fact that **fixing it made the
engine worse**, from -249 to -330 Elo. The constraint was never the activation; it was
153,372 training samples for 197,000 parameters.

The NNUE inference itself ships and is tested. A net can be loaded with
`setoption name NetFile value <path>`. None is enabled by default.

These are self-play figures and self-play inflates, since two builds of the same
engine share every blind spot. Roughly 60% typically transfers.

Reproduce any row:

```bash
./gradlew build
java -cp build/classes/java/main strix.harness.Main 4 20000 1200 runs/x.tsv "Ordering=false" 100
```

**Strength against outside opposition: being measured now, publicly.**
[lichess.org/@/antoinepamplemousse](https://lichess.org/@/antoinepamplemousse)

The bot plays rated 3+2 games against other Lichess bots, continuously, as a launchd
agent. The rating on that profile is the one number in this project that does not
depend on trusting the author, and it is not one I control.

Until it has played enough games the rating is marked provisional and means very
little, so no figure is quoted here. Read it off the profile.

It had to be made to seek games. The client shipped on 2026-09-16 and only ever
*accepted* challenges, which meant eight days of perfect uptime and zero games,
because nothing challenges an unrated bot nobody has heard of. See
[ADR 0015](docs/adr/0015-seek-rated-games.md), including the daily bot-versus-bot cap
that a naive retry loop sleeps through, and the fact that Lichess puts challenges you
*send* on the same event stream as the ones you receive.

The self-play numbers above compare Strix to Strix, and stay labeled as such.

No claim here asks you to trust the author.

## Quickstart

```bash
./gradlew test                        # includes the perft suite
./gradlew run                         # speaks UCI on stdin
```

To play against it, point any UCI GUI (Cute Chess, Arena, BanksiaGUI) at the built jar.

## Playing on Lichess

The bot needs a BOT-account token in `.lichess-token` (gitignored). Then:

```bash
ops/install-bot-service.sh            # builds the jar, installs the launchd agent
ops/bot status                        # also: logs, stop, start
```

It plays rated 3+2 by default, one game at a time, and challenges online bots rather
than waiting to be challenged. Arguments are `<token-file> <maxGames> <clockSeconds>
<increment>` if you want something else.

**One game at a time is a strength decision, not politeness.** The harness runs at
fixed nodes, where two games sharing a core give identical results to one
([ADR 0011](docs/adr/0011-nodestime-over-wall-clock.md)). On a real clock a second game
halves the nodes this one searches, so a rating collected under contention is a rating
for a slower engine.

Restarts are free: `rejoinOngoing` picks up games already in progress, because
abandoning a live game on the clock is a loss and a bounced process is not a chess
result.

## Running the harness across machines

One laptop can only settle changes big enough to be obvious. SPRT games scale
roughly as 1/effect², so the transposition table at +33.5 Elo took 770 pairs, but a
+5 Elo change needs about 34,000 — days of a single machine. `strix.cluster` splits
the games across processes, on one machine or many.

```bash
java -cp build/classes/java/main strix.cluster.Main coordinator 9000 500 60 runs/cluster.tsv 5
java -cp build/classes/java/main strix.cluster.Main worker http://<host>:9000 w1 20000 - -
```

**The hard part is not the fan-out, it is the accounting.** A worker can die
holding a job, and over a network a dead worker and a slow one look identical:
silence. Jobs are leased with an expiry and reclaimed when it lapses, which means a
result can arrive *after* its job was reassigned. The same pair then gets submitted
twice.

Counting it twice would be invisible. `Sprt.record` cannot tell a repeat from a
real observation, so a double-counted pair moves the log-likelihood ratio exactly as
far as a genuine one, and the run stops early on evidence that does not exist. The
Elo number comes out wrong with nothing to indicate it.

So the append-only log is the source of truth for what has been counted, and a
duplicate is dropped before it reaches the test. Dropping rather than reconciling
is only safe because games run at fixed nodes per move ([ADR 0011](docs/adr/0011-nodestime-over-wall-clock.md)):
two workers computing the same pair produce the identical result, so there is never
a question of which one is real.

Killing a worker mid-game, 8 pairs, one machine:

```
counted 0  pending 7  inflight 1   worker holds a lease, then is killed -9
counted 2  pending 4  inflight 2   a second worker takes over
counted 6  pending 1  expired 1    the dead lease lapses, its job is reclaimed
counted 8                          16 games, 1 lease expired, 0 double-counted
```

Two empty responses are deliberately different. `503` means no job right now,
`204` means no job ever again. Conflating them is a silent, terminal bug: every
surviving worker quits the moment a dead worker's jobs are the only ones left, the
lease expires with nobody there to claim it, and the run ends short while looking
finished. `ClusterServerTest.anEmptyQueueIsNotTheEndOfTheRun` pins it.

This distributes **games, not search**. The search itself stays single-threaded and
deterministic, for the reason given under "What this is NOT".

### Leaving it running overnight

A long run meets failures a short one does not, and most of them are not the
network. Both machines must be told not to sleep, the coordinator must come back if
it dies, and both sides must be running the same build.

On the coordinator:

```bash
caffeinate -i bash -c 'until java -cp build/classes/java/main \
  strix.cluster.Main coordinator 9000 40000 60 runs/overnight.tsv 5; do sleep 5; done'
```

`caffeinate -i` blocks idle sleep for as long as the command runs. The `until` loop
restarts the coordinator if it exits badly, which is safe because startup replays
`runs/overnight.tsv` and resumes: a crash costs the pairs that were in flight, not
the ones already on disk.

On each worker machine:

```bash
caffeinate -i java -cp build/classes/java/main \
  strix.cluster.Main worker http://<coordinator-host>.local:9000 friend 20000 - -
```

Use the `.local` hostname rather than a raw address. DHCP leases get renewed
overnight, and a worker pointed at an address that moved will retry for fifteen
minutes and then exit.

Workers ride out transient failures on their own: a request that fails retries with
exponential backoff for up to fifteen minutes before giving up. Retrying a result
POST is safe for a reason that already existed, which is that the coordinator
deduplicates by job key. The dedupe was built for lease expiry and covers
at-least-once delivery from a retrying worker without change.

**Check both machines are on the same commit.** Nothing enforces it yet, and a
worker on a different build silently mixes two engines into one statistical test.
That corruption looks exactly like a real result.

```bash
git rev-parse HEAD        # must match on every machine
```

In the morning:

```bash
curl <coordinator-host>.local:9000/status
wc -l < runs/overnight.tsv
```

## Key design decisions

Each ADR records what else was considered and what the choice cost.

- [0001](docs/adr/0001-bitboards-over-mailbox.md) Bitboards over a mailbox array
- [0002](docs/adr/0002-magic-bitboards.md) Magic bitboards for sliding pieces
- [0003](docs/adr/0003-pseudo-legal-generation.md) Pseudo-legal generation with a legality filter
- [0004](docs/adr/0004-make-unmake-and-packed-moves.md) Make/unmake in place, moves packed into an int
- [0005](docs/adr/0005-alpha-beta-not-pvs.md) Alpha-beta, and why negamax is kept forever
- [0006](docs/adr/0006-quiescence-is-mandatory.md) Quiescence search
- [0007](docs/adr/0007-transposition-table.md) Transposition table
- [0008](docs/adr/0008-move-ordering.md) Move ordering
- [0009](docs/adr/0009-repetition-detection.md) Repetition detection
- [0010](docs/adr/0010-sprt-normal-approximation.md) SPRT: the normal approximation, and why it was replaced (superseded)
- [0011](docs/adr/0011-nodestime-over-wall-clock.md) Fixed nodes over wall clock, for reproducibility
- [0012](docs/adr/0012-sprt-bounds-match-effect-size.md) SPRT bounds must match the effect size you expect
- [0013](docs/adr/0013-texel-tuning-rejected.md) Texel tuning, measured and rejected
- [0014](docs/adr/0014-nnue-rejected.md) NNUE, measured and rejected
- [0015](docs/adr/0015-seek-rated-games.md) The bot challenges, it does not wait
- [0016](docs/adr/0016-openings-must-not-repeat.md) A replayed game is not a second observation

`docs/devlog.md` has the bugs, including a green build that ran zero tests and a bug
injection that a node-count test happily passed.

## What this is NOT

- **Not competitive with serious engines.** [Calvin](https://github.com/kelseyde/calvin-chess-engine)
  and [Serendipity](https://github.com/xu-shawn/Serendipity) are the strong Java engines.
  This is not trying to be them, and it cites them as references and differential-test oracles.
- **Not an opening book or endgame tablebases.** Those improve results without
  demonstrating anything about the search. They are data, not engineering.
- **Not a parallel search.** Parallel search is nondeterministic, which would break the
  reproducibility every verification step here depends on. `strix.cluster` distributes
  whole games across workers, which keeps each game deterministic; it does not
  parallelise the tree.
- **Not a GUI.** Speaking UCI means it does not need one.
- **Not a chess variant engine.** Standard chess only.

## Training the evaluation

Both attempts at a learned evaluation were rejected on measurement
([0013](docs/adr/0013-texel-tuning-rejected.md),
[0014](docs/adr/0014-nnue-rejected.md)), and both failed for the same reason: not
enough independent data. The trainer now refuses to repeat it.

```bash
java -cp build/classes/java/main strix.tune.Trainer runs/labelled.txt runs/net.bin 30
```

```
72 independent samples / 197,377 parameters = 0.00036x

REFUSING TO TRAIN.
```

**Independent samples are games, not positions.** 198,014 positions drawn from 6,000
games is about 6,000 independent samples, because 33 positions from one game share a
pawn structure and a piece set. The first NNUE run had 0.8x, fewer examples than
unknowns, and that was computable before any training code ran.

| flag | what it does |
|---|---|
| `--curve` | trains on 10/30/60/100% of the *games* and reports held-out loss for each. A falling curve says more data helps and the slope says how much; a flat one says data is not the constraint |
| `--force` | trains anyway, below the floor |

The holdout is split by **game**, never by position. Splitting by position puts
near-duplicates of the training data into the validation set, which is how 0.0086
held-out loss, the lowest figure this project ever recorded, sat next to -249 Elo.

## Testing

```bash
./gradlew test                        # unit + perft suite
./gradlew perftDeep                   # depth 6, slow, runs nightly not per-commit
```

## License

MIT
