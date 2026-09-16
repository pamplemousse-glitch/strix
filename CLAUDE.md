# Strix: working agreement

A UCI chess engine in Java. This file is binding on any AI assistant working in this
repo. It exists because the project's value is Antoine's ability to defend it out loud,
not the code itself. Code he cannot explain is worth less than no code.

## Why these rules exist

Measured findings that shaped this file:

- **METR RCT (Jul 2025):** 16 experienced devs, 246 real tasks. AI-allowed tasks took
  **19% longer**. Devs predicted 24% faster. A 43-point perception gap. So: never pace
  this project on how fast it feels.
- **GitClear (211M lines):** refactoring fell from 25% of changed lines (2021) to under
  10% (2024). Copy-pasted lines rose 8.3% to 12.3%. Duplicated 5+ line blocks rose **8x
  in one year**, because AI inserts new blocks instead of reusing existing functions.
- **Willison's "cognitive debt":** losing track of how agent-written code works. Distinct
  from technical debt. The code may be fine; the author is the one who is broken.
- **Osmani's 70% problem:** AI is fast at scaffolding and happy paths. Edge cases, error
  handling, and integration cost exactly what they always did.

Cognitive debt is the specific failure mode that loses a Visa technical deep-dive round.
Everything below is a countermeasure.

## The rules

1. **Spec before code.** Work the numbered steps in `docs/build-plan.md`, in order. One
   step at a time. Never a prompt that spans multiple steps.

2. **Failing test first.** Every step. The test is written and observed failing before the
   implementation exists. No exceptions, including for "obvious" code.

3. **One step, one commit.** No batching. Review happens after each step, not at the end.

4. **The understanding gate, and it is ACTIVE.** Amended 2026-09-15: Antoine asked that the
   assistant write the code. That is fine, but passive review is the cognitive debt trap, so
   the gate is not "read the diff and nod." Before each commit the assistant MUST run one of:

   - **Prediction question.** Not "do you understand?" but a question only answerable with a
     working model: "what happens if alpha and beta are swapped on this line?", "which perft
     position breaks if I delete this castling check?", "why is this `>>>` and not `>>`?"
   - **Bug injection.** Hand over a version with one subtle defect and have Antoine find it
     before running the tests. Perft settles it. This directly rehearses the current
     interview bar: catching flaws in AI-generated code.

   If he cannot answer, do not commit. Stop and teach, or rewrite it simpler.

5. **Antoine drives all perft debugging.** Superseded the hand-writing rule on 2026-09-15.
   Debugging is investigation, not typing, and it is the single best understanding-builder
   here. The assistant is a rubber duck for this, never the debugger. Antoine also owns every
   ADR decision and all tuning.

6. **The assistant hunts duplication explicitly.** Every few steps, ask "does this already
   exist in the repo?" AI will not volunteer this; see the GitClear finding above.

7. **Trust the number, not the assistant's confidence.** Every step has an external oracle
   (exact perft counts, answer-preserving invariants, a real GUI). When the assistant's
   confidence and the number disagree, the number wins.

8. **ADR at the moment of decision**, never afterward. The reasoning evaporates in about
   48 hours. Four lines: what I chose, what else I considered, why, what it costs.

9. **No dead code, no TODOs in main, no commented-out blocks.** If a feature is not done,
   cut it from the README.

10. **Never fake a number.** Any number claimed anywhere in this repo has a command in
    this repo that regenerates it.

## Verification is the point

Every step in the build plan has an external oracle. That is deliberate, and it is why
chess was chosen over a typical CRUD project: **there is nowhere for a plausible-looking
bug to hide.** A subtly wrong en passant rule returns 4,865,594 instead of 4,865,609 and
we both find out in seconds.

Use AI heavily. Structure the work so AI cannot hide anything.

## Repo conventions

**Home:** `github.com/pamplemousse-glitch/strix`. Public from the first commit.

**Git:** one branch and one PR **per stage**, four total (`stage-1-rules`,
`stage-2-search`, `stage-3-harness`, `stage-4-nnue`). Clean commits inside each, one per
numbered step in `docs/build-plan.md`. Never commit directly to main.

**Layout:** a single Gradle module, with packages arranged so they could be split into real
modules later without moving code.

```
strix/
  src/main/java/strix/
    core/      Board, MoveGen, Fen, Zobrist, Perft
    eval/      Evaluator (interface), Material, Psqt, Nnue
    search/    Negamax, TranspositionTable, Ordering, Quiescence
    uci/       UciLoop, TimeManager
    harness/   MatchRunner, Sprt, Worker        (Stage 3)
  src/test/java/strix/
    PerftTest, InvariantTest, ...
```

The Stage 3 harness lives in this repo. One CI, one README, one story. Extract later only
if it earns its own life.

**Package boundaries are enforced by review, not the compiler**, since it is one module.
`core` depends on nothing. `eval` and `search` depend on `core`. `uci` depends on `search`.
Nothing depends on `uci`. If a change violates that, say so before writing it.

## Style

- Java 25 (current LTS). Gradle (Kotlin DSL). JUnit 5.
- No em dashes anywhere, including comments and commit messages.
- Commit only when asked.
- Match surrounding code. Comment density stays low; the tests carry the explanation.
