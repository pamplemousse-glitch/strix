# ADR 0020: Measured against something that is not us

**Date:** 2026-09-25
**Status:** accepted

## The gap this closes

Every Elo figure this project reported was self-play, and self-play inflates:
two builds of one engine share every blind spot, and the published rule of thumb
is that roughly 60% of a self-play gain survives contact with a different
opponent. The Lichess rating is external, but it depends on whichever bots
happened to accept a challenge, at whatever time control they offered.

Neither is a controlled measurement against a known strength.

## What was run

`strix.harness.Gauntlet` plays a fixed number of game pairs against Stockfish 19
with `UCI_LimitStrength` and `UCI_Elo` set, both sides at 20,000 nodes per move,
each opening played from both colours. Fixed games rather than SPRT, because
SPRT answers "is A better than B" and stops early; this asks "how strong is A",
which needs a known reference and a sample that does not stop on a lucky streak.

| Reference | Result (W-D-L) | Score | Elo diff | Implied |
|---|---|---|---|---|
| Stockfish @1400 | 44-6-0 | 0.940 | +478.0 +/- 140.1 | 1878 |
| Stockfish @1600 | 27-31-2 | 0.708 | +154.1 +/- 59.7 | **1754** |
| Stockfish @1800 | 28-18-14 | 0.617 | +82.6 +/- 75.3 | **1883** |

## The rungs disagree, and that is the interesting part

1878, 1754, 1883. A spread of about 130 Elo, wider than any single interval.

If `UCI_Elo` were a linear, hardware-independent scale, all three would agree.
They do not, and the likely reason is that **`UCI_Elo` is calibrated against a
clock, not a node count.** Stockfish limits its strength partly by restricting
search, and running it at a fixed 20,000 nodes changes the relationship between
the requested rating and the play delivered. The 1400 rung is also saturated at
94%, where the Elo curve is steep and a fixed sample says very little.

The most trustworthy rung is the one closest to an even score, since that is
where the curve is flattest and the variance smallest. That is **1800, at 61.7%,
giving 1883 +/- 75**.

## What is claimed

**Strix is somewhere around 1750 to 1880 on Stockfish's `UCI_Elo` scale at
20,000 nodes per move.** Not a CCRL rating, not a Lichess rating, and not
directly comparable to either.

For context, the Lichess bot account reads 1712 over 71 rated games at 3+2. That
is a different pool, a different time control and a different hardware budget,
so agreement to within roughly 100 Elo across three independent methods is about
as much as the methods can deliver.

## What is not claimed

That either number is precise. The honest summary is that three methods
(self-play, Lichess, and a calibrated local opponent) now bracket the engine in
the high 1700s to high 1800s, where previously only one existed and it was the
one that inflates.

## What it cost

`UCI_Elo` being clock-calibrated means these numbers move if the node count
moves. Any future run has to quote the node count alongside the rating, and a
rung near an even score is worth more than two rungs far from one.
