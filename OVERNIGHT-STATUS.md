# Overnight status

Read `bench-harness-plan.md` (v4) for the design and `optimization-plan.md` for the candidates.
This file is the state of play.

## Done and green

**Plan finalized through three review rounds** (two with a reviewer holding context, one held-out and
blind). Each round found real defects; the third found that the plan's own centerpiece statistic was
unsound.

**Phase 1, repair the instruments.** Committed, 17 oracle checks green.
**Phase 4, bytecode.** Committed, 13 checks green.
**The verdict statistic** (the heart of phases 2 and 3). Committed, 21 checks green. Replicate legs,
pooled spread, t threshold at a stated alpha Bonferroni-corrected over 15 rows, common-mode drift
measured but not double-charged, and a minimum detectable effect on every flat row. The harness now
states its own resolution: **flat to within 2.44% at df=3**, which means the open +4.3% regression is
detectable and anything under ~2.4% is not.
**Ten candidate optimizations.** Written, with hypotheses, predicted fields, and falsifiers.

**Phase 3, the A/A null.** Committed. Control legs run against each other through the identical
pipeline, so any row it classifies is false by construction. Tested in both directions: silent on
quiet controls, and it catches a deliberately dirty null.

Suites: **92 checks green** across four files (44 BenchTest, 17 oracle, 12 bytecode, 19 stats).

## The results that matter

**A first explanation for `continuationBodiesFuse` +4.3%**, open since before this session and never
diagnosed. The two suspension arms are not symmetric: `Suspend` is expanded into the drive loop,
`SuspendWith` calls a separate 607-byte `dispatch$1` at bci 821 of a 1582-byte loop. `ask.map{...}`
used to mint a `Suspend` and now mints a `SuspendWith`, moving this row's whole per-iteration path
from the inlined copy to a non-inlinable call, 1000 times per op. Six rows made the same move and got
faster; this is the only one whose continuation body is itself too big to inline, so it alone cannot
repay the frame. **An isolation experiment needing no source change is ready** (add the frame to
`36b41336fb` and measure).

**Every inline refusal in the kernel was warmup noise.** 1929 of 1969 refusals are C1; `callee is too
large` is 1166 C1 against **0** C2. The harness was reporting all of them. `Stack::push` refused at
7 of 8 sites, which seeded three analyses, is a red herring twice over.

**100% of the hot row's allocation is kernel node shapes.** `anon$95` reads like the benchmark's
lambda and is the kernel's `Arrow.Suspend` node. 32,080.04 B/op is two 16-byte kernel objects per
iteration.

## Bugs found and fixed in the harness

Seven, five of them in code written tonight:

1. Morphism reported 46 of 47 sites as "measured polymorphic" with receiver counts of zero.
2. Deopts counted 633 compiler-planted guards as 6 runtime events.
3. Inlining folded per-method, so one warmup site decided a verdict; two runs of one comparison named
   disjoint mechanisms.
4. Runtime deopts modelled as task children when all 6 precede the first task: dropped silently.
5. Planted traps anchored on `bci=`, missing the 121 that lead with `method=`.
6. `c2Tasks` read 0 in both stored production runs for a fork that performed 88.
7. OSR tasks read 0 of 5, because they carry `compile_kind` before `method`.

Four of these are one root cause: **matching elements by attribute order instead of by shape.** I
reintroduced it three times while fixing it once.

## Not done

- **Phase 2 remainder**: config-from-json equality gate, diffstat scope, heap pinning.
- **Phase 3 remainder**: leg orchestration to actually *run* C V C V C (the comparison and null are
  implemented and tested; the runner still produces one leg per invocation), and the classfile
  equality guard.
- **Phase 5** allocation attribution via `output=collapsed`.
- **Phase 6** the investigator with efficacy-gated flag falsifiers.
- **Phase 7** guardrails and QA that can fail.
- **Phase 8** known-answer fixtures. **The most important gap**: every validation so far is a null,
  and a harness tuned only against nulls converges on abstaining.
- **No measurement has been run tonight.** Nothing here rests on a fresh benchmark; it rests on the
  captured artifacts and on static reading.

## Open decisions defaulted (reversible, flagged for ruling)

Assembly automation dropped; unified JFR dropped in favour of `output=collapsed`; two Tier A
falsifiers cut (megamorphism unstatable at 12 profiled sites of 5093, GC already pinned);
`PrintEliminateAllocations` taken; heap and collector pinned; Phase 4 scoped to named methods;
`bench-harness-qa.md` deleted in favour of per-phase acceptance criteria; A/A moved from 3 legs to 5.

## Standing constraints honoured

No kernel source was edited. No candidate was landed. No `inline` added. No PR touched. Every commit
under your identity with no attribution.
