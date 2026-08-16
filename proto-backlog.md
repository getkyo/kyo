# Proto kernel backlog (autonomous session, user out)

STATUS: all queued items done, 78/78 proto tests green, 7 commits on the branch (997576a9bc..2b13581c52),
tree clean. Full session report in the chat. Boards: bench-results/proto-kernel-v16.json (final runtime),
proto-kernel-v17.json (inline-match A/B, rejected), compile-bench-v2-proto-reds.json (compile reds re-run:
MapChainDeep100 +22% -> +0%, ForCompDeep25 +15% -> +2%, EffectRowGenerics +9% -> +1%, MapChain10 -8%,
MapChainWide100 +15% -> +8% residual).

Working rules in force: no casts without prior validation (audit-sanctioned removals are fine, additions are not), no
new mutability, no major architectural changes, no nested inline defs, no code comments beyond existing style, quality
over speed. Never edit sources while a benchmark runs. Commit early and often on this branch. Identity fwbrasil@gmail.com.

## Queue (in order)

1. [x] Rename `Nested.box` -> `Nested.nest`, `Nested.strip` -> `Nested.unnest` (user verbatim: "let's rename to
       nest/unnest for clarity"). Sites: Pending.scala defs + map arm, CanLift.scala splice + comment, ArrowEffect.scala
       x7, Eval.scala wrapper. Test, commit.
2. [x] Code-audit cast clears (report from proto-cast-audit-code, R1-R7):
       - R1 Pending.scala:25-26: nest body loses both casts (opaque transparent there now).
       - R6 Pending.scala:28-31: unnest folds to a single erased cast.
       - R2 Arrow.scala Identity.apply: stop rebinding `next`/`v`; drop 2 casts. Verify bytecode: no Nested.nest call
         appears in those arms (silent conversion check).
       - R3 Eval.scala:40,:122,:152: add `private[proto] inline def fromAny[S](inline v: Any): Any < S = v` in
         Pending.scala (plain widening inside opaque scope, no cast); replace the 3 casts. NEVER use liftValue at :152
         (settled is union-repped; nest would double-wrap).
       - R4 Stack.apply/pop return Arrow[Any, Any, Any] (casts move to the storage boundary, net -2); Eval:22,:25,:67,:151.
       - R5 Eval:120-121: match `case a: Arrow[Any, Any, Any] @unchecked`, drop the cast.
       - R7 Stack state surface widens to Any (cast-neutral, localizes storage adaptation).
       Skip D1-D5 (audit recommends against or impossible). Test, commit.
2a.[x] Port Loop into the proto (user: "you need to port Loop as well because of Outcome, prioritize accordingly").
       New kyo/kernel/proto/Loop.scala from kernel2 kernel/Loop.scala: Continue1-4, Outcome1-4 opaque types,
       continue constructors returning `Outcome[...] < Any` in the PROTO `<` (the standing cast stands in for the lift,
       Continue is never Boxed; kernel2's design comment documents the three failed bare-return designs, keep it),
       bare done constructors, and the drivers (apply/indexed/foreach/repeat/forever/whileTrue) adapted:
       `case kyo: Kyo[?, ?]` becomes `case kyo: Arrow[?, ?, ?]`, suspended re-entry via map re-lifts raw outcomes.
       Switch proto Arrow.scala/ArrowEffect.scala/Eval.scala imports from kyo.Loop to proto Loop. Test, commit.
3. [x] Test-audit cast clears: rewrite the `continue`/`continue2` helpers in PendingTest/ArrowEffectTest/EvalTest to use
       the proto Loop directly, dropping the bridge casts. Check ArrowEffectTest:69 `inner.asInstanceOf[Int < Ask]`
       (variance should make it a no-op ascription). Test, commit.
4. [x] Soundness fix (audit section 1, delivery contract): bare `suspend`'s cont re-lifts, HandleLoop/HandleLoopState
       answer arms at Eval.scala:88 and :102 pass union-repped `o` straight to `s(o)` -> double-wrap
       Nested(Nested(arrow)). FIRST write failing test (bare suspend of Give + handleLoop answering
       continue(settled(ask-backed computation)) + map over delivered payload), confirm red for the right reason, THEN
       fix: `cur = s(Nested.unnest[Any](o))` at both arms. Green, commit.
5. [x] ArrowEffect *With overloads (user verbatim): handleWith / handleLoopWith / handleLoopStateWith, continuation in a
       NEW SEPARATE param group. Keep existing methods untouched; copy the code (no shared inline helper, inline nesting
       is expensive). Handle node already carries `cont: Arrow[B, C, S]` (currently always Arrow[B]) so the With
       variants build the cont arrow from the function. Tests for each. Commit.
6. [x] Finish Pending.scala surface (reference kyo-kernel/kernel2 Pending; adapt, no multi-effect handlers): flatMap,
       andThen, flatten, unit; evalNow if it fits without new machinery. Tests for each. Commit.
7. [ ] Measurement pass (NO source edits during): compile bench red fixtures
       (MapChain10,MapChainDeep100,MapChainWide100,ForCompDeep25,EffectRowGenerics protoKernel, use -rf json this time;
       the b6xs9bvfr rerun output was truncated and lost) + runtime rows (uncachedValuesPayBoxingOnly,
       fusionAllocatesNothing, suspensionBaseline, trailingMaps) on the post-clear tree; then A/B map strict-arm
       inline-match vs current plain unnest call; decide the delivery shape on combined compile+runtime; commit winner
       with numbers.
8. [x] Reply to proto-cast-audit-code agent (courtesy close-out). Sent: section-1 confirmed + third site (resume
       settled arm) found and fixed; R1 premise corrected (opaque transparent in companion object < only, NOT in
       object Nested; nest's casts load-bearing); R2-R7 applied; bytecode verified.

## Measurement pass

- DECIDED: map delivery shape stays the plain static Nested.unnest call. v17 (inline-match at the site) vs v16
  (plain call) is within +-2% on every row (fusionAlloc 0.55 both, uncached 33.78 vs 34.19, trailingMaps slightly
  worse at +2.1%); the inline variant buys nothing at runtime and costs bytes per expansion site plus a cast.
  Probe reverted, tree matches the committed plain-call shape.
- ATTRIBUTED: the fused-row residual vs v8 is the plain-strip delivery design itself, not today's batch.
  Evidence chain: gc-profiled run shows zero alloc growth (fusionAlloc 0.00 B/op, uncached ~155 KB/op = its
  historical boxing norm), so pure cycles; probe P2 (Identity.apply R2 reverted) 0.543 = no recovery; probe P3
  (unnest fold R6 reverted) 0.543 = no recovery; timeline: v15.json (fusionAlloc 0.518) was measured at
  964f88aa78, BEFORE cd51da65e4 "plain strip delivers", so the 0.51->0.545 window is exactly that design change,
  and v17 shows spelling the strip inline at the site does not recover it. Recovering the ~7% on
  fusionAlloc/continuationBodiesFuse would mean returning to the per-arm carry composition, which the user
  traded away deliberately against inline-nesting compile cost; their call, documented in the report.
  uncached single-row sessions read 33.36 (+3% vs v8), the 34.19 in the full board was partly session noise.
- After: compile bench red fixtures (MapChain10, MapChainDeep100, MapChainWide100, ForCompDeep25,
  EffectRowGenerics) with -rf json; fixtures unchanged for v1-board comparability (verified: no Loop usage,
  handle-only, still compile against the new tree).
- Follow-up noted, not this pass: re-port fixtures-proto ForComp*/FlatMapChains to the real flatMap (now exists).

## Second wave (user-directed, after the measurement pass)

9.  [x] Cast review of all commits (user: "make sure there aren't other cases"): compiler-probed 4 claimed-necessary
        casts (all error on removal), behaviorally probed Eval:151 (removal compiles but 9 nesting tests fail via the
        silent lift), found + removed one unnecessary cast (bench idle-handler row, contravariance ascription,
        3385289aa4). fromAny deleted (e9157e89a2): unnecessary indirection, vacuous check at A=Any; the two
        representation assertions are explicit casts, the unit entry lifts.
10. [x] Implicits port (c71ad11b6a): proto Implicits trait (row-parametric lift, abortCastUnit, liftPureFunction1-6,
        Render given); object < extends it; Arrow conversion renamed fromArrow; liftValue removed; liftInternal
        deliberately NOT ported (proto proved same-module expansion works). ImplicitsTest ported (18 tests).
        eval ext carries the evidence-backed cast (S =:= Any in hand; substituteCo blocked by inliner proxy artifact).
11. [x] EffectTrace analysis (proto-effecttrace-analysis.md) + implementation (f10d7e6a81): reconstruct-on-throw port,
        Suspend gains frame (populated in suspend/suspendWith, forwarded in chain), Eval.loop wired at dispatch /
        settle-complete / step-application / unhandled, loop-level splice before the truncating finally; the
        step-application attach carries the folded `next` chain (the proto's dump-fold moves pending continuations
        off the Stack, so the fold, not the stack, holds what was left to run; kernel2's `applied = whole` equivalent).
        7 trace tests: clause throw, continuation-site throw, unhandled enriched, nested drives accumulate
        innermost-first, NoStackTrace, cap drop, fatal untouched. 103/103.
12. [x] v18 runtime board (final EffectTrace shape, suspended as Maybe, commit 1207b41222): pure-map rows flat
        (fusionAlloc +0.1%, uncached -1.0% vs v16); suspension/handler rows pay for the wiring:
        suspensionFusesContinuation +6.2% (err 0.23), trailingMaps +5.6%, continuationBodiesFuse +4.5%,
        handleLoopAnswersInPlace +4.5% (err 3.45), deepRecursion/suspensionBaseline ~+2%. Suspects: suspended var
        stores (optional feature: settle-lead frame), try ranges in loop arms (irreducible for reconstruct-on-throw),
        Suspend.frame accessor on the chain re-mint (irreducible for suspension-site rendering). USER RULING PENDING:
        accept, or probe the suspended-var share first.
13. [x] Null audit reported: JDK-contract nulls (Exception ctor, StackTraceElement), array-slot sentinels
        (Stack pop/truncate + tags-as-marker, Safepoint cell pool), CanLift = Null representation (convertible to
        Unit on request), EvalTest:99 stash var (convertible to Maybe on request).
14. [x] Gap analysis vs kyo-kernel reported: pillars = Context/ContextEffect design fork (stack-carried vs threaded),
        partial eval + preemption (Armed bit exists, consume/rebuild missing), Effect.catching/defer, Isolate
        (blocked on Context); smaller: Eval ??? arms, handleFirst decision, platform splits, fixture/bench debt.

## Third wave (user rulings applied)

15. [x] No handleFirst, ever: handler surface diverges by design. unsafeGet withdrawn (it is unnest/evalNow).
16. [x] Effectful clause outcomes FIXED (22c675072d, doc proto-effectful-clauses.md): 3 reproductions grew to a 7-test
        on the Eval ??? arms (inherited from kernel2's unfinished migration). THE NEXT WORK ITEM: interpreter frame
        for outcome resumption (Continue arm = resume-adapter dispatch; done arm = region exit, index-independent,
        marker found by identity; dump-fold means the OutcomeResume frame can ride chains, so the done arm must
        return a loop-interpreted region-exit node rather than mutate from apply).
17. [x] Clean-build breakage found and fixed (c12d01e34a): batch compile could not resolve the cross-file reference
        to static Stack.current from Eval (probes ruled out visibility and initializer shape; Nested statics resolve
        fine; narrow compiler quirk). current() left the static set WITH user approval; the thread-local stays static.
        Note: incremental builds masked this all day; any fresh checkout would have failed.
18. [x] Benches fixed (db32ea1362): fixtures-proto ForComp*/FlatMapChains re-ported to real for comprehensions
        (only-import-swap vs kernel fixtures, validated through the harness); ProtoKernelBench gains
        nestedPayloadsUnwrapInMaps (nest/unnest round trip) and handleLoopFusesContinuation (fused cont on the
        region node). Measurement pass in flight (task b0u381prw): v19 runtime board + compile-bench-v3 for the
        four re-ported fixtures.

## Deferred / flagged, not in this pass

- Eval.scala:81 and :94 `???` arms (HandleLoop/HandleLoopState outcome computation suspends). Real reachable branch;
  needs an interpreter-frame design like the mature kernel's; user said no major architectural changes, so surfaced in
  the final report instead.
- Invariant note for Arrow.scala:110 (Suspend.cont receives raw payloads only): report, not comment.
- CanLift `liftValue` use from another package (macro-splice accessibility check) once proto is used outside kyo.kernel.proto.

## Reference numbers

- Compile board v1 (pre-accessor-fix reds): MapChainDeep100 proto 9,912 vs new 8,119 (+22%).
- Plain-strip runtime regression pre-accessor-fix: uncached +12%, fusionAlloc +8% vs v8/v14 refs; accessor fix
  (76d915c232) changed the hot path, so remeasure before judging.
