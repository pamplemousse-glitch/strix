# ADR 0012: SPRT bounds must match the effect size you expect

**Date:** 2026-09-16
**Status:** accepted

## What I chose
The `elo1` bound is a parameter of every test run, not a constant. Roughly:

| Measuring | Bounds |
|---|---|
| A small patch | `[0, 5]`, Fishtest's default |
| A tuning change | `[0, 15]` |
| A whole feature ablation | `[0, 100]` or wider |

## What went wrong first
The first real measurement, full Strix against a build with move ordering
disabled, ran with Fishtest's `[0, 5]` bounds. After 71 pairs the observed score
was **0.7993, about +240 Elo**, and the LLR was **+0.92**, nowhere near the 2.94
bound.

Nothing was broken. `[0, 5]` asks "is this worth at least 5 Elo", and those two
hypotheses are 0.0072 apart in score. Against an observed mean of 0.80 **both fit
terribly**, and the likelihood ratio between two equally bad fits grows slowly.

Rerunning the identical data with `[0, 100]` gave an LLR of **+23.01** and settled
immediately.

## The symmetric trap, which is worse
The first transposition-table measurement returned **H0_ACCEPTED after 48 games at
-29 Elo**, with bounds `[0, 100]`.

Read carelessly that says "the transposition table is worthless." It does not. It
says **"the table is not worth 100 Elo"**, which is true, uninformative, and
extremely easy to misreport.

This is the more dangerous direction, because a fast confident-looking rejection
invites you to delete a feature that was actually helping.

## What it costs
Every run now needs a judgement about the expected effect size before it starts,
and getting it wrong wastes compute in one direction or produces a meaningless
verdict in the other. Tight bounds on a small effect cost a great many games:
detecting 0 from 5 Elo took roughly 18,000 games in simulation.
