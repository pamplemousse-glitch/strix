#!/usr/bin/env python3
"""
Lichess fishnet-evals parquet -> the `fen | cp | gameId` format strix.tune reads.

WHY THIS DATA
  The first NNUE attempt trained on 153,372 positions drawn from about 6,000
  self-play games by a ~1500-strength engine, labelled at depth 8, and measured
  -330 Elo. See ADR 0014.

  These labels are Lichess fishnet at 1,000,000 nodes per move, a median depth
  of 21, which is roughly 600x more search per label. They cost a 43-second
  download rather than 18 hours of local Stockfish.

WHY THE GAME ID MATTERS
  Positions from one game are not independent: they share a pawn structure and a
  piece set. The ratio guardrail in Trainer counts GAMES, and a holdout that
  splits by position puts near-duplicates of the training data into the
  validation set, which is how 0.0086 held-out loss sat next to -249 Elo.

  Game boundaries are detectable without any game metadata: a FEN with fullmove 1
  and black to move is the position after White's first move, so it starts a new
  game.

FILTERING
  Follows the conventional set, with the reasoning for each inline below.
"""
import argparse, random, sys
import pyarrow.parquet as pq

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-game", type=int, default=2,
                    help="positions sampled per game; more raises the count but "
                         "not the INDEPENDENCE, which is what the guardrail counts")
    ap.add_argument("--min-fullmove", type=int, default=14,
                    help="skip the opening; Stockfish's own recipe uses ply 28")
    ap.add_argument("--max-cp", type=int, default=10000)
    ap.add_argument("--seed", type=int, default=12345)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    game_id = 0
    kept = written = skipped_mate = skipped_cp = skipped_early = 0
    buf = []

    with open(args.out, "w") as out:
        for path in args.inputs:
            pf = pq.ParquetFile(path)
            for rg in range(pf.metadata.num_row_groups):
                tbl = pf.read_row_group(rg, columns=["fen", "cp", "mate"])
                fens = tbl.column("fen").to_pylist()
                cps = tbl.column("cp").to_pylist()
                mates = tbl.column("mate").to_pylist()

                for fen, cp, mate in zip(fens, cps, mates):
                    parts = fen.split()
                    if len(parts) < 6:
                        continue
                    stm, fullmove = parts[1], parts[5]

                    # New game: the position after White's first move.
                    if fullmove == "1" and stm == "b":
                        if buf:
                            picked = buf if len(buf) <= args.per_game else rng.sample(buf, args.per_game)
                            for f, c in picked:
                                out.write(f"{f} | {c} | {game_id}\n")
                                written += 1
                            buf = []
                        game_id += 1

                    # A mate score is not a centipawn value and clamping it would
                    # teach the net that every mate is worth the same. Dropped.
                    if mate is not None or cp is None:
                        skipped_mate += 1
                        continue
                    if abs(cp) > args.max_cp:
                        skipped_cp += 1
                        continue
                    # Opening theory is memorised, not evaluated, and it is also
                    # the most duplicated part of any game database.
                    try:
                        if int(fullmove) < args.min_fullmove:
                            skipped_early += 1
                            continue
                    except ValueError:
                        continue

                    # THE LABEL SIGN, and getting this wrong silently destroys
                    # the entire dataset.
                    #
                    # Lichess publishes cp WHITE-relative. The network is
                    # SIDE-TO-MOVE relative: Network.featureIndex maps own
                    # pieces to 0-383 and enemy pieces to 384-767 with the board
                    # mirrored, so its input is IDENTICAL for a position and its
                    # colour-flipped twin. There is no input bit telling it who
                    # is White.
                    #
                    # Feed it white-relative labels and the expected target for
                    # a given own-advantage a is
                    #     0.5*sigmoid(a) + 0.5*(1-sigmoid(a)) = 0.5
                    # exactly. Material is not merely hard to learn, it is
                    # analytically cancelled. That is how a net trained on 2M
                    # games reached a validation loss 6% better than predicting
                    # a constant and could not tell a queen up from a queen
                    # down. See ADR 0021.
                    if stm == "b":
                        cp = -cp

                    buf.append((fen, cp))
                    kept += 1

                print(f"  {path} rg {rg + 1}/{pf.metadata.num_row_groups}: "
                      f"{game_id:,} games, {written:,} written", file=sys.stderr)

        if buf:
            picked = buf if len(buf) <= args.per_game else rng.sample(buf, args.per_game)
            for f, c in picked:
                out.write(f"{f} | {c} | {game_id}\n")
                written += 1

    print(f"games        {game_id:,}")
    print(f"eligible     {kept:,}")
    print(f"written      {written:,}")
    print(f"skipped mate {skipped_mate:,}")
    print(f"skipped |cp| {skipped_cp:,}")
    print(f"skipped early{skipped_early:,}")

if __name__ == "__main__":
    main()
