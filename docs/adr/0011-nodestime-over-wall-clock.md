# ADR 0011: Measure with nodes, validate with the clock

**Date:** 2026-09-16
**Status:** accepted

## What I chose
Strix-vs-Strix measurement runs at **fixed nodes** (`go nodes N`), and Strix also
supports Stockfish's **`nodestime`** option, where a real clock is allocated
normally and then spent in nodes rather than milliseconds.

Games against Stockfish and on Lichess use a real clock.

## What else I considered
Wall-clock time controls for everything, which is what Fishtest does by default
(10+0.1 short, 60+0.6 long).

## Why
Wall-clock testing measures your machine's background load as much as your engine.
The same match run twice produces different games because something else wanted the
CPU. That noise lands directly in the Elo estimate.

Verified rather than assumed: `go nodes 200000` from the same position returned
`bestmove b1c3` three runs out of three.

## What it costs, and it is not small
**`nodestime` cannot see a change in nodes per second.** Make the engine 20% faster
and a fixed-node test reports exactly nothing, because it still gets the same node
budget. Worse, a change that improves evaluation quality at the cost of speed looks
like a pure win under fixed nodes and may be a loss on a real clock.

So this does not replace clock testing, it splits the questions:

| Question | Method |
|---|---|
| Is this search or eval change better? | fixed nodes |
| Did I just make the engine slower? | real time control |

Anything claimed about strength gets a real-time-control check before it is
believed.
