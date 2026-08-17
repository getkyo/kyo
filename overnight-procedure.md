# Overnight procedure

Your instruction, as a procedure I can execute unattended, plus the gaps I think it has. Written
before starting so the morning can check what I did against what I said I would do.

## The instruction, restated

1. Implement `bench-harness-plan.md`. High code quality, kyo primitives used properly, type-safe.
2. The goal that ranks above the others: **maximize the signal deterministic code produces for me**,
   so the tool guides the work instead of waiting to be asked.
3. When the implementation is ready, launch an opus review of it.
4. Then QA, with a twist: QA it *by using it* on a real potential optimization.
5. So an optimization plan is needed first: **10 concrete candidate optimizations, deliberately
   different in character**. Built by opus agents.

## Stages

### Stage 0: preflight

- Fold in the opus review of the *plan*, which is running now and which you asked for as a gate.
  Implementation does not start before its findings are read and answered.
- Commit the two uncommitted harness sources (`LogCompilation.scala`, `QaLogc.scala`).
- Untrack the 1390 `.scala-build` artifacts and add a `.gitignore`. They make every diff unreadable
  and make the tree's own cleanliness guard noisy.
- Confirm the measurement worktree is detached and clean.

### Stage 1: implement phases A through F

Order is the plan's, because the dependencies run that way: A cuts the default cost, B replaces two
runs with the unified JFR collection, C adds bytecode, D is the investigator that consumes B and C,
E is the guardrail on D, F is QA of the whole shape.

Per phase: implement, run the self test, commit. A phase that ends red still gets committed, with the
red stated in the message.

Quality bar I am holding myself to, since you named it: parse results into typed models rather than
passing strings around, `Maybe`/`Result`/`Abort` instead of null and exceptions, `Chunk` over `List`,
effects kept in effects, no `inline` anywhere without asking you first.

### Stage 2: opus review of the implementation

Adversarial brief, held out from my reasoning, same as the plan review. Every finding is a fix, not a
note. If it lands a structural finding I re-review after fixing.

### Stage 3: the optimization plan

Opus agents fan out to produce **10 candidates with different characteristics**, deliberately spread
across mechanism rather than ten variations of one idea: allocation removal, bytecode size against
the inline budget, call-site morphism, dispatch shape, field and layout, branch ordering, algorithmic
structure, and so on.

Each candidate carries, or it is not a candidate:

- the hypothesis, in one sentence
- the mechanism it would change, named specifically
- **which harness field would move if it is true**, and by roughly how much
- **what result would falsify it**
- what it costs to try
- whether it needs anything gated: `inline`, a cast, a public surface change

The list includes the live one: `continuationBodiesFuse` is still +4.3% against the pre-SuspendWith
design, allocation byte-identical, inlining and megamorphism both ruled out. It is the honest subject
for this exercise because I could not close it by hand.

### Stage 4: QA by doing

Run candidates through the tool, cheapest-decisive first. Two things are under test at once, and the
second matters more:

- the candidate: does it help, and does the evidence say why
- **the tool: did it hand me the complete picture without being asked?** Every time I had to reach for
  something manually, that is a defect in the tool and it gets fixed and recorded.

## Rules in force all night

**Machine exclusivity.** No subagent, build, or background job runs while a measurement runs. You
caught a saturated CPU before and it invalidates whatever was being measured. Agents and measurements
strictly alternate; that serialization is the reason the night is planned in stages rather than run
in parallel.

**Nothing lands in the kernel.** Candidates are measured in the detached throwaway worktree only.
Kernel changes are yours to approve; I will arrive with measurements and diffs, not with commits to
proto sources. Same for `inline` and for casts: a candidate that needs one can be *measured* and
cannot be *landed*.

**Inconclusive is a real result.** If a candidate's evidence does not identify a mechanism, that is
what the report says. The tool is being built specifically to stop me from asserting mechanisms I did
not measure, so I do not get to do it in its report either.

**Open decisions do not block.** The plan has four. I take the defensible default, record it, and
flag it for you rather than stalling until morning.

**Your TODOs stay yours.** Any I find in code get committed so they cannot be lost, and not acted on.

**Git.** Commit as I go under your identity, no attribution trailers, no session links. No PR
interaction of any kind, at any point, for any reason.

## What I think you are missing

1. **The plan review is a gate you set and it is still running.** Starting implementation without
   reading it wastes the review you asked for. Preflight folds it in.
2. **Measurement and agents cannot share the machine.** The single biggest threat to a useful
   morning: a night of numbers taken while an opus agent saturated the CPU. Hence exclusivity above.
3. **The twist needs a landing rule.** "Use the tool to work on an optimization" runs straight into
   the standing gate that kernel changes need your approval. I resolved it as measure-but-never-land.
   If you meant I may land a winner, say so and I will.
4. **A tool that helps is the deliverable, not a tool that runs.** Stage 4's real output is the list
   of moments the tool left me guessing. I will keep that list explicitly.
5. **Run arithmetic bounds the night.** A comparison is 2 legs plus drift plus rebuilds. I will
   calibrate the real wall clock in stage 1 and report how many candidates actually fit rather than
   promising ten measured by morning.
6. **Durability.** Results, dossiers and the optimization plan go in the repo, not `/tmp`, and get
   committed as they are produced.
7. **1390 tracked build artifacts** exist in the harness directory. Fixed in preflight.

## If something blocks

I keep going on everything that does not depend on the blocked thing, finish it, and state plainly
what is left and why. Three failed attempts with three novel failure modes and nothing further to
learn independently is a real block; "this is hard" is not.

## Morning deliverable

- Phases A-F implemented, committed, self test green, QA phases re-run.
- The implementation review, and what I changed because of it.
- The optimization plan: 10 candidates, characterized as above.
- Results for the candidates that fit the night, each with evidence or an explicit "inconclusive".
- The tool defect list from stage 4.
- The open decisions I defaulted, for your ruling.
