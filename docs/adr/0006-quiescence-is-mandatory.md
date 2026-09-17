# ADR 0006: Quiescence search

**Date:** 2026-09-15
**Status:** accepted

## What I chose
At depth 0, keep searching captures and promotions until the position is quiet.
"Stand pat" on the static evaluation as a floor, since you are never obliged to
capture.

## What else I considered
Not having it. Simply evaluating at depth 0.

## Why
Observed directly rather than argued. After 1.e4 e5 the engine reported **+100 at
depth 3 playing Nf3**, then **-100 at depth 4**. Alpha-beta and negamax agreed at
every depth, so the search was provably correct: at depth 3 it sees Nxe5 winning a
pawn and the recapture lands one ply past the horizon.

That is the horizon effect, and without quiescence the engine hangs pieces
constantly. After adding it the score from that position is a flat 0 at every
depth.

## What it costs
**This is the first thing in the project that is not answer-preserving.**
Quiescence deliberately changes the result, so it cannot be verified by comparison
against negamax. The negamax invariant test now runs with quiescence disabled on
both sides, which keeps the reference pair pure.

Node cost is real and unmeasured. Measuring it needs Stage 3.
