# kernel2 review issues: tracker

State as of this morning: the authorized queue is COMPLETE. Every issue below is
either done (committed with the full kernel2 JVM suite green), closed by your ruling,
or waiting on you. Nothing outside kyo-kernel2 was touched.

Working rules in force: "fix" authorizes without re-asking, one issue per green-suite
commit; the tracker carries only what needs your attention; every issue is its own
item; all changes confined to kyo-kernel2; every command logged and watched.

# Needs your attention

## 18c. Rotation implemented: review the landed round
- On your "proceed", rotation landed in the real kernel end to end: handle
  loops with Rotate steps replace the chain search; the Handlers parameter (a
  Chunk of fun-format handlers, maintained like the context) answers their
  operations locally; bindings, catching, and observation share the one
  mechanism; prepend, Interceptor, ContextBinding, evalOperation, prefixArrow,
  and hasHandler are deleted.
- Suite 623 of 623, including the former context-scope red and five new pins
  carrying the old-kernel-validated values. Board: pure-path rows identical;
  dispatch rows 2.8x to 4.4x faster with allocation halved or better; one noted
  regression, contextRead100 at +11% time and +8 B per read (rotate node per
  read bounce), routed to the optimization round.
- Semantics resolution to be aware of: handling is eager again (old-kernel
  construction-time acting); two tests that pinned lazy installation were
  re-pinned accordingly.

## 27. `object Kyo`'s utility surface belongs at `kyo.Kyo`
- Blocked by design until the swap round: the kyo-test runner classpath carries the
  old kernel, and `kyo.Kyo` would collide (the same collision that kept the package
  `kyo.kernel2`). The move is mechanical when the old kernel leaves the classpath.

# Done: the overnight ledger (each with the full suite green)

| # | Issue | Commit |
|---|-------|--------|
| 2 | Forwarding methods removed | `564dc4cc9f` |
| 5 | Type parameter naming, Suspension covariance | `9b47ace3af` |
| 6 | drive/dispatch verbs consolidated to eval | `04adb36022` |
| 7 | Informative node renderings, depth-bounded (kyo-test hang diagnostics render computations; unbounded toString wedged the runner; AndThen carried the same latent hazard) | `8ca699fac0` |
| 8 | `(x: Any) match` widenings removed | `eba87074f4` |
| 10 | `discard` renamed `finalizeBracket` | `a9d5624818` |
| 25 | `Chunk` for `List` in the finalizer family (your instruction) | `7ef7fca9de` |
| 1 | `Kyo.lift` explicit nesting body, `Implicits` base class in `Implicits.scala`, `unwrap` renamed `unnest` | `40b715c811` |
| 20 | `Step`/`step`/`handlePartial` restricted to `private[kyo]` | `3fa2cad56f` |
| 11 | `Observe.apply` in its own file with an effectful observer | `32f78f8e3b` |
| 12 | Trace carrier renamed `EffectTrace`, `Trace` stub deleted | `43d5c021a7` |
| 9, 28 | Typed `prepend` via `Arrow.Interceptor` (one documented cast); `runDetached` moved to `ContextEffect` | `8440e2e013` |
| 14 | `LastResort` removed: handlePartial loops around the plain drive | `25bb4f1f07` |
| 3, 4, 29 | Typed handlers storing clauses at their public types, `ArrowHandler`/`ContextBinding` naming, PURE INSTALLATION (no evals at handle sites), clause throws unwind to the enclosing Catching | `daf66e9585` |
| cast sweep | Subtyping-proven casts removed, `fromKyo` conversions instead of node casts, nesting-safe lift in Observe; the rest sit under justification comments | `c0229dfe1c` |
| 26 | Node hierarchy moved to `kyo.kernel2.internal` (old-kernel layout) | `9118d5a4b4` |
| 15, 21, 23 | Preemption integrated: drives poll the Safepoint in three modes (Preemptible/Masked/Cascade), `Parked` renamed `Preempted` with total fields, Overflow deleted for a detached cell-backed fallback that keeps delivery, slice deadline for single-threaded runtimes, masked bracket releases; all preempt/period parameters gone | `863a0b87f0` |
| 17 | Tailrec loop drivers, allocation only on suspension (benchmarked first) | `ef7d29c956` |
| 18 | Context threaded as an execution parameter, old-kernel faithful: reads as Defers, bindings as typed re-arming interceptors, drives supply the context, public application defers, ContextRead/ContextSnapshot and the chain walks deleted, bracket release under region interceptors; measured better-or-equal everywhere except JIT-profile shifts on dispatch rows proven allocation-identical by probe (report section 2) | `c1c1a6f09a`, `94b020c89b`, `0496b6b1c7` |
| multi-shot combinators | All collection combinators replay-safe (indexed Chunk snapshots, immutable loop state, your ruling); reproduction-first, plus the finding that nested-resume multi-shot diverges identically on BOTH kernels (spliced continuations, conformant), guards use the terminating captured-continuation shape | `c68ac9ce1c`, `208134314f` |

Closed by your rulings: #13 (track C boundary doc rejected; the boundary rides #18's
context threading), #16 (single-allocation defer dropped; unit-fixed constructor
nicety noted), #19 (Context/TypeMap dropped entirely), #22 (Maybe slots dropped),
#24 (clearPreempt analysis accepted; its nested-boundary rule is enforced by the
mode split: only handlePartial consumes).

# Performance record

After the preemption integration (vs the interim conformance ledger):

| row | ledger | now |
|-----|--------|-----|
| eagerMap5 | 5.67 ns/op | 4.62 ns/op (Overflow deletion made `thread` total, removing a hot-path type test) |
| suspension | 480 ns / 2,136 B | 441 ns (typed handlers removed the +16 B dispatch closure) |
| state10 | 826 ns / 4,576 B | 828 ns / 4,560 B |
| narrowIter | 2,539 ns / 12,040 B | 2,517 ns / 12,024 B |
| stateMap10k | 2.12 ms | 2.06 ms |
| deepBind10k | 68.5 us | 66.6 us |
| resumeFused | 18.5 ns | 18.4 ns |

eagerMap5 and resumeFused remain allocation-free; the +0.9 ns on eagerMap5 versus
the 3.71 no-preemption floor is the preemption capability's measured price.

Loop drivers (issue 17, before vs after the tailrec port):

| row | before | after |
|-----|--------|-------|
| loopPure10k | 76,883 ns / 320,344 B | 18,912 ns / 160,008 B (4.1x; the remaining 16 B per iteration is the outcome carrier) |
| loopSuspend1k | 63,922 ns / 379,934 B | 71,439 ns / 379,938 B (about 7.5 ns per resumed iteration from re-entry type tests, on a dispatch-dominated path; recorded honestly) |

# Simplification round: typed inputs, Kyo reorganization, central lift

State as of this round (all changes in kyo-kernel2):

- Transform.run takes its typed input again: `run[C, S2](v: A, ...)`. The erased
  `v: Any` parameter was an allocation-avoidance trick and is removed per ruling;
  minted transforms consume `f(v)` directly, ContextEffect reads take `v: Unit`,
  bracket use takes `v: R`, Loop step transforms take their outcome types.
- The user-facing combinator surface moved out of the kernel-internal node file:
  `kyo/kernel2/Kyo.scala` is the public object (combinators only), the node
  hierarchy lives in `internal/KyoInternal.scala` (object `internal.Kyo`).
- Lifting is done only by the pending type's central lift methods. The implicit
  route stays in Implicits.scala backed by LiftMacro; the runtime nesting wrap is
  `LiftMacro.defaultLift` (the macro emits it, kernel raw re-entry sites call it
  directly on their erased currency). Public `Kyo.lift` is the old kernel's
  `= v`: zero cost, and the explicit route for intentional nesting since the
  macro settles pending types at expansion. `Nested` no longer escapes
  kyo.kernel2.internal; `fromKyo` (Kyo => <, free retyping) already exists in
  Pending.scala.
- All `@static` annotations removed (Safepoint had 11, internal.Kyo.unnest had
  one). Root cause of two separate "value X is not a member of object" compile
  mysteries (unnest, then Safepoint.pollPreempt) that survived clean rebuilds;
  empirically tied to @static on object members under sbt/zinc with Scala 3.8.4.

Removed-for-now optimizations to revisit at the end of the work, each only with
a measured win and a compile-stability check:

1. Erased `v: Any` Transform.run parameter (megamorphic call-site erasure).

Probed and settled (not on the re-add list):

- `@static` on Safepoint accessors: restored under a clean build and measured;
  no effect on any dispatch row (suspension, state10, loopSuspend1k, narrowIter
  identical with and without). Stays removed.
- `@static` on unnest: fails compilation even on a clean build once the defining
  file is named KyoInternal.scala (companion class and object in a file whose
  name differs from the class). Permanently dead.
- `defaultLift` must be `inline`: as a plain method it broke escape analysis in
  the resume path and cost stateMap10k +48 B per iteration (3.59 MB vs 3.11 MB
  per op) and +39% time at JIT steady state. Short-warmup runs hide this: the
  first probe reached a different compiled state at baseline numbers, so board
  probes must use full-length warmup.
- `unnest` inline probe: in flight for the remaining ns-only delta on
  loopSuspend1k (+9%) and narrowIter (+7%), both alloc-identical to baseline.

Next round: unsafe-code cleanup. Type the rotate machinery (`Rotate` with real
type parameters instead of `Arrow.Transform[Any, Any, Any]`), the handle loops'
currencies (old-kernel-style existential captures at the tag-matched arm), and
rewrap. Casts remain only at documented tag-keyed and trampoline boundaries. The
Bracket arm's loop-wrapped acquire is a known typing pressure point to resolve
during that round (an aborting ctl handler inside acquire currently completes
the acquire with the handler's value; the typed rewrite must settle the intended
semantics with a pin).

# Type-safety round: closed

Rotate/rewrap/loops typed end to end (commit bd688eda04); the Finalize.scala
and Eval.scala centralization preceded it (commit cc446cf27b). The typing
exposed and fixed the bracket acquire corruption under non-resuming handlers
(commit e7fa57585b, three pins, old-kernel semantics). Suite 626/626, board
clean, loopSuspend1k's +2.1 ns/iteration from typed Loop inputs remains the
only known cost, recoverable via the erased-input re-add item.

# Cleaning round: measured findings

- Maybe replaces the null answer protocol at zero cost once the Maybe match
  lives in its own method instead of apply's inlined body (first attempt paid
  the escape-analysis breakage again: stateMap10k +48 B/iteration and
  contextRead100 +16 B/read, both from body-size inflation alone since the
  branch never executes on those rows).
- The minted transforms' inlined Offset fast path is load-bearing: routing
  them through apply instead regressed narrowIter +30% and stateMap10k +16
  B/iteration. Reverted. The bytecode-size answer is a shared non-inlined
  helper with the same shape, name pending.
- Loop drivers: the five non-indexed step transforms hoist to one lazy val
  per loop entry; loopSuspend1k -16 B/iteration (163,960 B/op, below the
  pre-typed baseline). The indexed family captures the index and keeps the
  arm-local mint. The residual gap to the old kernel is the two-node fusion
  currency per suspension, owned by the handler encodings design.
- The encodings design's live defect is confirmed and pinned: same-tag
  innermost-wins breaks across a park when the operation surfaces mid chain
  (the outer resume entry steals it at the bubble point, obtained 102 for an
  expected 12), while tail surfacing behaves because the empty-continuation
  fast path returns before handlers are consulted. Known red pin in
  ArrowEffectTest until the encodings implementation lands the entry
  discipline.


# Handler encodings round: closed

The suspension-point dispatch landed end to end: Entry.Resume/Stop/Shadow in
the threaded Handlers, the stop skip (bare pass-through), the resume
registration for the loop's own resumptions, the shadow discipline for
ctl/first/loop, and the NeverResumed static skip for Const[Nothing] outputs
minted at suspend. The innermost-wins-across-a-park defect is fixed for all
three formats. A second live defect fell out of the pins: the acquire
pending-ness probe evaluated the by-name thunk twice; rebuilt brackets now
carry a settled mark and the unsettled fold defers to the drive.

Headline rows: neverResumes1k 16ns/64B, deepStop1k 2.77us/22.3KB. Known
costs: state10 +16B per op (per-call shadow entry), contextRead100 alloc
+25-33% with time improved 15-25% (escape-analysis shape, flagged; the E2b
array-backed carrier round owns the read path). Open items from the design:
the miss-path mitigations gated on foreignBubbleUnderStop, entry-kind int
dispatch, boundary stop entries for handlePartial, and the resume entry
self-extension.


# Regression round: closed

The order was to fix every regression on the board. Outcome per row:

- state10: fixed. The ctl/first/loop formats allocated their shadow entry
  eagerly per handle call; Rotate.masked and the rewrap adapters now take the
  tag and mint the entry only when an outer same-tag entry is visible. Back
  to 1,040 B/op exactly, time flat (ac5dd6968b).
- contextRead100: the encodings-era 24.0-25.6KB readings were a mode that no
  longer exists. Cause: the encodings grew rotate past the JIT inlining
  threshold with the bracket rebuild machinery, costing callers the escape
  analysis of their per-rotation closures. Extracting the bracket arm into
  rotateBracket (b3c8f610bb) removed that mode. What remains is a per-fork
  bistability {19,240 | 20,824 B/op} that is byte-identical at the
  pre-encoding commit (15/10 iteration split on both, five clean forks each):
  inherited, not introduced.
- stateMap10k: per-fork bistable {3,111,843+-16 | 3,591,883 B/op}, exactly
  48 B/iteration between modes, and the pre-encoding commit shows the same
  two modes. Sampled bias: baseline 10/15 forks good vs HEAD 6/16; Fisher
  p > 0.1, not evidence of a shift. No demonstrated regression; the bad mode
  predates the encodings.
- loopSuspend1k +2.1ns/iter: the typed-Loop-inputs cost, on the erased
  re-add list by prior ruling; untouched.

Method notes, hard-won this round:

- Shape-flip measurements are only valid on clean builds. Incremental
  rebuilds across shape changes produced phantom readings in both directions
  (a 19,240 "recovery" and a 3,591,884 "regression" that clean builds
  overturned). Protocol: kyo-kernel2JVM/clean, tolerate the one-off
  "No matching benchmarks" flake with a rerun, then measure.
- The two rows above are per-fork bistable; single-fork numbers are samples,
  not values. Protocol for them: -f 5 minimum and compare mode histograms
  (grep the per-iteration gc.alloc.rate.norm lines), never single-fork
  summaries. The deterministic rows (eagerMap5, deepBind10k, suspension,
  suspensionStep, state10, resumeFused, loopPure10k, loopSuspend1k,
  deepStop1k, stopConstructed1k, neverResumes1k, foreignBubbleUnderStop)
  stay byte-stable across forks and keep the single-fork protocol.
- neverResumes1k's true clean-build value is 48 B/op (the 64 in the
  encodings record was a dirty-build sample).
- A rotateSuspend extraction (the suspension arm out of line, mirroring
  rotateBracket) measured fully neutral on clean builds and was dropped:
  the smaller committed shape wins.
- JFR observation suppresses the stateMap10k bad mode (four of four
  profiled forks landed good): the profiler perturbs the inlining race, so
  it cannot catch the 48 B/iteration delta in the act. Stabilizing the
  bistability, if ever wanted, is an EA-currency question (the iteration's
  minted transforms and resume closure), owned by the same kernel-wide
  node-currency design as the two-node fusion residual.


# Cleaning cycles round: closed

The order was: resolve the in-code TODOs, then three quality passes
(overengineering and leftovers, safety and cast removal, final coherence),
perf-gated throughout. Six commits.

TODO sweep (5fd0a61a36): the Context/Handlers de-leak landed via
publicInBinary on a private[kyo] execution apply, bytecode-verified accessor
free and measured clean on every row; respine renamed linearize per the
annotated request; the Defer-field and Loop-currency questions answered in
place with the measured findings (16B layouts round equal under compact
headers; the suspension crossing's extra node is the kernel-wide two-node
currency, not Loop-local).

Cycle 1 (136651fa59): optimize's count replaced by a single node budget
(fits); LiftMacro's three-mode enum collapsed; Observe.Step renamed Segment;
doc drift removed. The first fits cut decremented only at leaves and hung
the deep-resumed-continuation test (a deep left spine descends fully before
its first leaf); the budget now decrements per node, which is what bounds
the walk.

Cycle 2 (8c47bc9366): sixteen casts removed (104 to 88): Loop's binders
typed, Effect.bracket through fromKyo, yieldValue's lift row pinned, the
settled-position reads centralized in one typed Kyo.settled reader,
reacquire's rebuilt bracket marked settled, Context ops private[kyo],
publicInBinary on Arrow.empty and stepSlow. The remaining 88 casts sit at
the documented boundaries: tag-keyed dispatch, trampoline currency, opaque
narrows, row discharge, contravariant row widenings, macro emission.

Cycle 3 (this commit): scaladoc for the two bare central public types
(the pending type and Arrow), Transform's comment promoted to scaladoc, a
boundary comment on the finalization walk's release cast, and the
arrow-mechanism doc synchronized with the landed shapes (Step, linearize,
fits, the resolved annotated questions).

Corrections to earlier records, learned at the gates:

- loopSuspend1k is fork-bistable too: {150,008 | 163,960 B/op}. Cycle 2's
  gate drew the low mode and the commit message credited publicInBinary
  with an improvement; the final gate drew the high mode again, so that
  attribution is withdrawn. The row moves to the histogram protocol.
- contextRead100's high mode has a +32B sub-variant (20,856), seen once per
  five-fork run twice; the mode set is {19,240 | 20,824 +- 32}.
- Context.isEmpty was briefly removed as dead and restored: IsolateTest
  asserts through it. The dead-code call had misread the grep hit's type.

Final state: suite 635/635; deterministic rows at reference (deepBind10k
160,336, loopPure10k 160,008, narrowIter 4,704, suspension and
suspensionStep 616, state10 1,040, resumeFused 16, deepStop1k 22,232,
stopConstructed1k 14,064, neverResumes1k 48, foreignBubbleUnderStop 5,992,
eagerMap5 0); contextRead100 3 of 5 forks in the good mode (the baseline
split), stateMap10k 5 of 5.


# Migration round, step 1: the old kernel's organization

kernel2 now compiles as package kyo.kernel with the old kernel's kyo-package
surface (c20ae5c131); prelude points at it (c9dc7756a4). Structural interim
state, all owned by the half-migrated stack: kernel2's tests and Jmh/run are
blocked by the kyo-test runner classpath still carrying the old kernel
through kyo-core (two kernels cannot share a classpath once both claim
kyo.kernel), so benchmarks run through the direct pipeline documented in
build.sbt. Board via that pipeline: all rows at reference; deepStop1k joins
the fork-bistable class with modes {22,232 | 22,248 B/op}, both drawn at the
pre-reorg commit through the same pipeline, so the reorg is exonerated. The
bistable protocol list is now: contextRead100 {19,240 | 20,824 +- 32},
stateMap10k {3,111,843 +- 16 | 3,591,883}, loopSuspend1k
{150,008 | 163,960}, deepStop1k {22,232 | 22,248}.


# Migration round, step 2: kyo-prelude compiles on the new kernel

All nineteen prelude sources compile against the reorganized kernel2, on
JVM, JS, and Native. The 345-error wall was package resolution; the real
API deltas were exactly two, both known in advance: handleCatching gained
the old kernel's accept input filter (deferred in the encodings round "to
the effect that needs it", which is Abort: an operation the filter rejects
crosses the handler structurally like a foreign effect, reaching outer
handlers), and Debug.trace was rewritten from Safepoint interceptors onto
Observe, the mechanism built for it. The dotty 3.8.4 scanner crash that
masked the diagnostics (NPE in observeOutdented while rendering errors
inside inlined code) disappeared with the errors themselves. Prelude's
tests are structurally blocked like kernel2's, and the deterministic board
is at reference after the change.
