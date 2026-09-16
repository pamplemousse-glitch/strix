# ADR 0010: SPRT via the normal approximation, with a minimum sample guard

**Date:** 2026-09-16
**Status:** accepted, with a known better target

## What I chose
SPRT with the pentanomial pair model, LLR computed by the **normal
approximation** using the observed mean and variance of the pair score. Bounds
`log(beta/(1-alpha))` and `log((1-beta)/alpha)`. Default `[0, 5]` Elo with
alpha = beta = 0.05.

Plus a guard: **no verdict before 16 pairs**, and zero observed variance returns
an LLR of 0 rather than infinity.

## What else I considered

**The exact GSPRT**, which is what Fishtest uses. It replaces the log-likelihood
with its maximum over the parameter space subject to H0 and H1, and expresses
bounds in normalized Elo so test duration depends only on the bounds rather than
on the draw rate or opening book. A reference implementation exists at
`gahtan-syarif/SPRT`.

**Not implementing SPRT at all.** The practical consensus among engine authors is
to use OpenBench, `fastchess`, or `cutechess` instead. The main community testing
guide does not even give the LLR formula, it just says which tool to run.

## Why
Not implementing it is correct advice for someone whose goal is a strong engine
and wrong advice here, because the harness is half of what this project is for.
Outsourcing it leaves nothing but a configuration file.

The approximation was chosen over the exact form because it is short enough to
understand completely, and understanding beats accuracy at this stage.

## What it costs, and this is the important part

**The minimum-sample guard exists only because of this choice.** The normal
approximation divides by an *observed* variance, which is exactly zero when few
samples all score alike. The exact GSPRT has no such failure mode.

Observed directly: a +40 Elo patch was rejected after 2 pairs with an LLR of
**-3,649,310,055**. See `docs/devlog.md`. Nobody documents a minimum-sample rule
for SPRT, and now the reason is obvious: in the correct formulation you do not
need one.

So `MIN_PAIRS = 16` is a patch over a weakness introduced by picking the easier
formula, not a general property of the test.

The approximation is also less accurate near the bounds than the exact form.

## Migration path
Port the exact GSPRT from the reference implementation. The synthetic test in
`SprtTest` is what makes that safe: it feeds results from a simulated player of
known strength and checks the verdict and its rate. That test caught the billion
LLR bug and would catch a botched port the same way.
