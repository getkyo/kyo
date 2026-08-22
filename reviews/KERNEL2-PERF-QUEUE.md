# Autonomous queue: kernel2 performance campaign

Goal, user's words: fully green against kyo-kernel. A red row means the kernel is not viable.

## Standing status

- 2026-08-22 answersCont ADOPTED at claim strength (`e8b389c241` impl, `7e00c88485` pins,
  full -f 2 board `reviews/bench/answerscont-0822-kernel2.json` from a detached worktree at the
  same commit): HandlerCont gains the F1 answers protocol (answersCont template, per-site clause
  binding, eval gate at pos 0/1). suspensionBaseline 186 to 88.6 (0.48x), suspensionFuses 99.8 to
  43.6 (0.44x, now beats turbolift's exact asksEff 49.6), continuationBodies 32.6 to 24.6 (0.75x),
  sharedHandler 161 to 126 (0.78x), foreign 868 to 774 (0.89x); every other row inside the drift
  band, micro rows unmoved (settled path untouched), +1.9KB/op budget-bail defers on the loop rows
  (named). Suite 987 green including six new cont-family hostile pins mirroring the F1 block.
  Score vs kyo-kernel: reds now 4: RunOnly 2.92x, foreign 2.50x, fusionAfterSuspension 2.40x,
  userTypes 1.10x (sharedHandler 0.95x and continuationBodies 1.04x now at or under the bar;
  suspension cluster green). Vs turbolift the remaining losses are suspensionBaseline 2.1x,
  foreign, trailingMaps, sharedHandler, deepRecursion. Named next mechanisms: fusionAfterSuspension
  and RunOnly are the pos>1 general path (chain re-fold per answer); foreign alternating tags exit
  the answers loop every iteration; the map-over-suspension attach fix (Effect.attach, proposed) is
  the remaining construction lever.
- 2026-08-22 null-to-Maybe cold slices DONE (`96f6c9b625`, plan `reviews/KERNEL2-NULL-TO-MAYBE-PLAN.md`):
  drainFinalizers takes Maybe[Throwable], Park snapshot carries Span[Maybe[Finalizer]]. Out.cont
  Maybe bracket still pending; hot/platform/evidence nulls stay per plan.
- Held-out Fable safety audit of the kernel running (writes reviews/KERNEL2-SAFETY-AUDIT.md).
- 2026-08-21 overnight, user-directed pivot: cross-library comparison DONE. Six boards (kernel2,
  old kernel, ZIO 2.1.26, cats-effect 3.7.0, zio-blocks-async 0.0.51, turbolift 0.126.0) in one
  same-session run; tables, fidelity labels, sensitivity checks in
  `reviews/CROSSLIB-BENCH-RESULTS.md` (raw JSONs `reviews/bench/crosslib-0821-*.json`, chain:
  CROSSLIB-BENCH-REQUIREMENTS, CROSSLIB-BENCH-PLAN, CROSSLIB-API-GROUND-TRUTH). Headline: kernel2
  beats every external on the eager/fused/stateful families; the suspension/handler-crossing
  cluster loses to Turbolift (suspension 4.4x, foreign 7.5x) and partly ZIO, the same rows that are
  red vs the old kernel, confirming the round-three targets. Boards gained rows (entryFloorBatch,
  dynamicChainOf{Maps,Binds}StaysLinear, adopted from zio-blocks' AsyncChainBench) and loop starts
  now read through the seed field (anti-folding, drift-checked 0.97-1.03x vs gate2). Isolate
  reintroduction remains the interrupted pending task.
- Tree: `11cd77a348`, clean, 976 tests green on JVM. ADOPTED at gate strength (same-session
  three-board run, no regression, worst internal mover 1.02x): F3 settled-eval fast path
  (`ff7c08817c`: evalFixedOverheadBatch 0 B/op, 0.17x of kyo-kernel) and F2-L1 fold-peel
  (`11cd77a348`: trailing 618us = 888x faster than kyo-kernel, RunOnly 3.40x to 2.93x,
  fusionAfterSuspension 2.59x to 2.36x; the 240,026 B/op drop is exactly one 24-byte Defer per
  level). F1 ADOPTED (`9370176c42`, byte-identical to the branch, 981 tests, gate passed same-session:
  stateful 91.82 vs kyo-kernel 145.36 = 0.63x GREEN, handleLoopAnswersInPlace 0.69x GREEN, Proto
  internal stateful 0.15x, handleLoopFuses 0.49x, emitting 0.86x, worst other mover 1.02x).
  Remaining reds vs kyo-kernel: RunOnly 3.01x, foreign 2.71x, fusionAfterSuspension 2.37x,
  suspension cluster ~1.4x, sharedHandler 1.22x, userTypes 1.11x — the round-three list.
- Previous baseline: `26f14ecdd4`.
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

1. [x] E1 `exp-stateful-register` DONE, NEGATIVE, do not adopt (branch exp-e1-register, commits ed39151070
   + a1e4fae07a, worktree agent-a56067a70d1a89b4a). The register removed the array-store escape and the row
   did not move (543 to 541us, B/op byte-identical): the load-bearing escape is the box being returned
   through h.run, a virtual call the JIT does not inline, not the slot store. Three non-target rows paid
   5-7% for the spill machinery: the cost floor of any register protocol in the shared loop. Implementation
   was clean (979 tests green, full spill inventory) and the falsification is the deliverable. With E4,
   the evidence now triangulates on the statically bound clause call as the old kernel's actual advantage.
2. [ ] E2 `exp-inline-drive`: per-call-site inline settled loop for handleLoopState, kyo-kernel style,
   bailing to the shared region. Must also measure compile-time cost (HandleSites fixture, kernel2 currently
   0.50x). Open question: the soundness guard for applying the continuation without the stack.
3. [x] E3 `exp-span-continuation` DONE, NEGATIVE, do not adopt (branch e3-span-continuation, commits
   8214b5c0b2 + 2279542e85, worktree agent-a6a2183859f104e7a). Spans re-expand the dumped range on resume,
   forcing the settled path to re-fold every op what the baseline folds once and reuses as a single AndThen
   entry: fusionAfterSuspension 1.55x, RunOnly 1.49x (1,264 to 1,720 B/op), trailing 1.07x. Two wins at
   byte-identical allocation (suspensionBaseline 0.95x, suspensionFuses 0.93x) say restore-beats-flatten is
   real on the pure-resume path; salvageable follow-up is cheaper AndThen flattening, not representation
   change. Two park-style casts in its Resume flagged for sign-off if ever revived.
4. [x] E4 `exp-morphism-probe` DONE (worktree agent-a161b3ee09690d980, commit 2718475ebd). Verdict: the
   cost IS morphism-dependent. Bimorphic clause site costs kernel2 +480,046 B/op (= +24 B per answer: the
   Continue2 outcome stops being scalar-replaced at two classes) in both JVM configs, and +23% time even
   with boxing free. The old kernel shows zero bimorphism penalty in any cell: per-call-site expansion makes
   every clause site monomorphic by construction. Also: kernel2 mono/default is bimodal across forks (~490
   vs 1100-3100us: the JIT-unstable mode is the stateful row's noisy band), and kernel2's best cell (boxes
   free, mono, 468us) is still 1.7x the old kernel's equivalent (271us), so box elision is necessary but
   not sufficient. Evidence favors the per-call-site inline family (E2) or any design removing the
   per-answer allocation structurally rather than via EA across a shared site.

Acceptance for whichever design is adopted: USER REVIEW FIRST, always — no exploration result is merged,
cherry-picked or re-implemented into the main tree without the user reviewing the diff, the exploration
report, and the flagged casts/public surface. Then, per adopted change: full KernelBench + ProtoKernelBench
on both kernels, same session, -f 3 on movers, tables shown, every row at or under 1.05x or the row is an
open defect. One adoption at a time, re-measured before the next.

## Round two (launched after E1-E4 all closed; E2's report re-read first)

E2's refutation was COMPOSITION-TIME driving (answering suspensions while building the value, before
any eval exists); the converged design runs the answer step at EVAL time inside the per-call-site
generated handler class, which E2's own verdict names as the sound home. The map expansion (per-site
TransformBase, f statically bound, Safepoint.enter-gated local execution) is the in-repo precedent.

5. [x] F1 `exp-answer-in-class` DONE, VERDICT ADOPT (branch exp-f1-answer-in-class, final report
   376cf5403c). stateful 597.77 to 90.81us = 0.63x of kyo-kernel; statefulTwiceBi==Mono to 1%
   (morphism-independent by construction); handleLoopAnswersInPlace 0.67x; bands from bimodal
   +-160-316 to +-1-2; compile cost unmeasurable; 981 tests; five Out-cell hostile pins green;
   two representation-contract kernel bugs found and fixed at root en route. One flip-sweep
   incident, repaired and recorded (b69b7293a5). Restructured post-ruling: templates inline in
   Handler, ArrowEffect is wiring; restructured form measured 0.89x of pre-restructure. Open for
   user review: single seam method; Answered-sum scaladoc on Out; flagged casts and private[kyo]
   surface (Out, nextAnswer, resuspend). Remaining reds are handleCont-family (F2 territory).
5b. [was] F1 original brief: the per-answer step moves into the generated HandlerLoopState class.
   Stage 1: single answer per dispatch, allocation-free state crossing (accessibility wall: user-site
   expansions name only public surface). Stage 2: eval-governed local multi-answer loop, state in a
   local, budget and the eval's stop honored; the two EvalTest pins E2 broke must stay green. Also
   measures the compile-time cost (HandleSites/SuspendSites).
6. [x] F2 `exp-suspension-cluster` DONE (branch worktree-agent-a205e32a91b7ad22f, report a2ca534b2d).
   Attribution: the cont-family red is composition Defers + per-suspension dispatch round trips +
   clause-boundary boxing, NOT the AndThen fold; foreign adds a Chain fold/walk per crossing (~25%).
   L1 ADOPT: AndThen.apply peels its first step t(v, cont) instead of deferring (same law, no node;
   24 B saved per application): trailing 0.80x, RunOnly 0.82x, fusionAfterSuspension 0.90x vs base;
   34-row screen no row above 1.04x, movers all wins; 976 green. L2 dropped on measurement (flat push
   compiled 2.4x larger). Law learned and pinned: Chain.apply MUST defer (tail may carry a region);
   AndThen immune by type. Remaining reds attributed for next steps: cont dispatch/boxing (F1's
   direction applied to HandlerCont), foreign fold/walk, RunOnly at the folded-continuation floor
   (1,240 B/op vs CPS 0).
7. [x] F3 `exp-small-reds` DONE (branch f3-small-reds, 41d8ee110b). evalFixedOverhead CLOSED
   red to 0.22x of kyo-kernel: batch probe exposed exactly one result box per settled eval (13.98
   B/op) escaping the non-inline Eval.apply boundary; .eval settled fast path fixes it (2ns, ~0 B,
   controls flat, 976 green). userTypes 1.12x mechanism NAMED, fix deliberately not taken:
   Safepoint.get's volatile AtomicReferenceArray slot read is ~18% of every settled row; the lever
   (plain read on ownership check, VarHandle on stop/CAS paths) needs a Stop-visibility argument
   and concurrency pins. QUEUED as a future lever worth ~18% on all settled rows.

## Held items (do not start while explorations run, they collide)

- 2.2 param-order fix, user-approved: handleLoopState clause follows kyo-kernel ordering (input before
  state). Touches ArrowEffect + call sites + tests; E1/E2 touch the same files.
- Rewrite reviews/KERNEL2-PERF.md: it still carries two falsified conclusions (the Aug 18
  YetAnotherProtoBench citation, and "structural to the stack design").
- Delete Arrow2.scala (design sketch, committed deliberately, must not land).
- Isolate/fork-transfer design doc (Park as the transfer vehicle, binding walk primitive): discussed and
  shaped with the user, waiting until the perf campaign clears.
- Null-to-Maybe audit, user's words 2026-08-22: "check where we use null in the kernel, we should
  migrate to Maybe if there's no significant cost, let's do that as a follow up after this
  optimization". Scoped by grep: the Out cell lanes (cont/state/input, Handler.scala), Stack's
  ring-buffer slot clearing and drain accumulators (Throwable | Null), Eval's dispatcher out.cont
  protocol tests and loop locals, Safepoint's slot cache. The hot-path lanes (Out, dispatcher tests)
  need a measured bracket per the concession discipline; the cold ones (drain accumulators, slot
  clearing) are candidates for a direct migration. Starts after the answersCont optimization lands.

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
