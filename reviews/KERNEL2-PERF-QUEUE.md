# Autonomous queue: kernel2 performance campaign

Goal, user's words: fully green against kyo-kernel. A red row means the kernel is not viable.

## Standing status

- Tree: `26f14ecdd4`, clean, 976 tests green on JVM.
- Landed this campaign, each measured: arm reorder (`b86a1cdf1a`), Safepoint enter/exit under the inline
  threshold (`9dcec36058`), flatten + normalized dump (`89fff362f4`), the Step/Region hierarchy split with
  AndThen (`e845ffb589`), Step as its own Cont (`12fd7f3dec`), eval dispatch extraction (`26f14ecdd4`,
  measured null on wall clock, kept for the halved loop body).
- Score vs kyo-kernel at `12fd7f3dec` (full boards, -f 2, same session): time reds
  statefulAnswersPaySuccessor 3.74x, fusionAfterSuspensionRunOnly 3.40x, foreignCrossingsPayRotation 2.63x,
  fusionAfterSuspension 2.59x, suspensionBaseline 1.46x, suspensionFusesContinuation 1.44x,
  continuationBodiesFuse 1.40x, sharedHandlerPaysDispatch 1.25x, handleLoopAnswersInPlace 1.21x,
  userTypesSkipKernelWrapping 1.11x. Alloc reds: RunOnly 0 to 1,264 B/op, fusionAfterSuspension 1.80x,
  continuationBodiesFuse 1.14x, suspensionBaseline 1.14x, uncachedValuesPayBoxingOnly 1.10x.
  Greens held: trailingMapsStayLinear 727x faster, uncachedValues 0.64x time, idle/rescue rows 0.94x,
  handleLoop rows recovered.

## Explorations in flight (isolated worktrees, results pending)

1. [ ] E1 `exp-stateful-register`: HandlerLoopState state as eval-loop register, slot as spill.
   Mechanism evidenced: per-answer box escapes via putState array store; AutoBoxCacheMax=20000 takes the row
   612 to 235 while kyo-kernel moves 144 to 136. Risk: win may not survive megamorphic h.run (E4 tests this).
2. [ ] E2 `exp-inline-drive`: per-call-site inline settled loop for handleLoopState, kyo-kernel style,
   bailing to the shared region. Must also measure compile-time cost (HandleSites fixture, kernel2 currently
   0.50x). Open question: the soundness guard for applying the continuation without the stack.
3. [ ] E3 `exp-span-continuation`: HandlerCont continuation as a Park-shaped span value; equation
   k(a) == Park(a, entries, states, empty). Targets the whole suspension cluster and RunOnly's 1,264 B/op.
   Regression guards: trailingMapsStayLinear, emitting row, capture/multi-shot pins.
4. [ ] E4 `exp-morphism-probe`: bench-only; does the box cost survive a bimorphic clause site, with and
   without AutoBoxCacheMax. Decides the E1-vs-E2 production relevance argument.

Acceptance for whichever design is adopted: full KernelBench + ProtoKernelBench on both kernels, same
session, -f 3 on movers, tables shown, every row at or under 1.05x or the row is an open defect.

## Held items (do not start while explorations run, they collide)

- 2.2 param-order fix, user-approved: handleLoopState clause follows kyo-kernel ordering (input before
  state). Touches ArrowEffect + call sites + tests; E1/E2 touch the same files.
- Rewrite reviews/KERNEL2-PERF.md: it still carries two falsified conclusions (the Aug 18
  YetAnotherProtoBench citation, and "structural to the stack design").
- Delete Arrow2.scala (design sketch, committed deliberately, must not land).
- Isolate/fork-transfer design doc (Park as the transfer vehicle, binding walk primitive): discussed and
  shaped with the user, waiting until the perf campaign clears.

## Undiagnosed rows nobody owns yet

- userTypesSkipKernelWrapping 1.11x time (alloc 1.00x): path length, no mechanism named.
- evalFixedOverhead 1.23x at 0.01us: below JMH resolution, needs a different instrument or an explicit
  dismissal.
- foreignCrossingsPayRotation beyond what E3 recovers, if any.

## Reference numbers (kyo-kernel, this machine, this morning, -f 2 -wi 5 -i 5)

stateful 144.24 / foreign 310.25 / suspBase 122.00 / suspFuses 68.01 / bodiesFuse 23.44 / sharedHandler
128.26 / hlAnswers 128.33 / fusionAfterSusp 82.59 / RunOnly 0.26 (0 B/op) / userTypes 41.36 / trailing
622,010. Full JSONs: reviews/bench/kernel2-0821-runtime-oldkernel.json.

## Session working rules in force

Edit tool for all source changes; validate optimizations with the user before landing in the main tree
(exploration in worktrees is authorized); tables after every full board run; -f 3 for claims; bench runs
serialize on /tmp/kyo-bench-mutex; never sbt --batch; commit as Flavio Brasil; no PR interaction.
