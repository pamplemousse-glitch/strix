# ADR 0005: Alpha-beta, and negamax kept forever

**Date:** 2026-09-15
**Status:** accepted

## What I chose
Negamax with alpha-beta pruning. Plain negamax is kept in the codebase
permanently, not deleted after alpha-beta worked.

## What else I considered
Principal Variation Search (NegaScout), which assumes the first move is best and
re-searches only when that assumption fails. Standard in strong engines.

## Why
Keeping the unpruned reference is the whole point. Alpha-beta is an
**optimization, not an improvement**: it must return the identical score having
looked at fewer nodes. Without negamax there is nothing to check that against.

This paid for itself immediately. A one-token bug (passing the recursive window
as `(-alpha, -beta)` instead of `(-beta, -alpha)`) broke the score, broke mate
detection, and broke material evaluation, while still **passing** the
node-reduction test, because a broken search still prunes plenty. Only the
comparison against negamax caught it.

PVS is deferred because it is worth a few Elo and there is currently no way to
verify a few Elo. That waits for Stage 3.

## What it costs
Negamax is dead weight at runtime and will never play a game. It is roughly 25
lines and it is the only reason the search can be trusted.
