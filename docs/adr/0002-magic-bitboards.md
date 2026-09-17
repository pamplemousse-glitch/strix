# ADR 0002: Magic bitboards, with the ray loops kept

**Date:** 2026-09-16
**Status:** accepted

## What I chose
Sliding-piece attacks by magic-bitboard lookup. `Attacks.rook` and
`Attacks.bishop`, the ray-walking versions, are **kept in the repo** and are what
the magic tables are built from.

## What else I considered
Staying on ray loops. Also `PEXT`, the x86 instruction that does this bit
extraction in hardware, which is what several C++ engines use. **Java exposes no
`PEXT` intrinsic**, so magics are the ceiling here regardless.

## Why
Sliding-piece attack generation is the hottest function in the engine. A rook's
attacks depend only on blockers along its own rank and file, so masking the board
down to those squares leaves at most 4096 patterns per square, small enough to
precompute entirely. Turning a pattern into a table index is one multiply and one
shift.

Two details that pay for themselves:

**Edge squares are excluded from the mask.** A blocker on the far edge changes
nothing, because there is nothing beyond it to block. That takes a rook from 14
relevant bits to 12, which is a 4096-entry table instead of 16384.

**Candidate magics are sparse**, generated as the AND of three random longs.
Dense candidates almost never work; sparse ones are found in a handful of tries.

## Measured

| position | ray loops | magic | |
|---|---|---|---|
| startpos perft 5 | 9.78M nps | 12.83M nps | +31% |
| kiwipete perft 4 | 8.86M nps | 11.69M nps | +32% |

Node counts byte-identical.

## Why the ray loops stay
This is an optimisation, not an improvement. It must return exactly what the slow
version returns, and the only way to prove that is to keep the slow version.

`MagicTest` checks 256,000 random **full-board** occupancies across all 64 squares
plus both extremes. Full-board matters: the tables are built from mask subsets, so
testing only those would be circular and would never catch a wrong mask. Bits
outside the mask must be ignored, and only a full-board occupancy proves it.

perft then proves it again at the level that matters.

## What it costs
Table generation runs at class load and searches randomly for each magic, which
takes a moment on startup. The seed is fixed, so the same magics are found every
run and a failure is reproducible.

Java notes: the multiply relies on 64-bit overflow wrapping, which signed `long`
does correctly for the low 64 bits. The shift must be `>>>`; an arithmetic `>>`
sign-extends and yields a negative index.
