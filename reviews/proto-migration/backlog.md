# Migrating `kyo-kernel` to the proto: the pending backlog

What `kyo.kernel` has that `kyo.proto.kernel` does not, after adjudication. Derived from a file and
signature diff of the two packages plus a read of the reference's eval dispatch, then ruled on item
by item. Items ruled out are kept with their reasoning, because "we decided not to" is information
the next reader needs as much as the list of work.

Everything here is about the kernel itself. Callers above it (`kyo-prelude`, `kyo-core`) are out of
scope for this document.

## Ruled in

### B1. `Isolate`

No proto counterpart at all. `kyo/kernel/Isolate.scala` in the reference.

What it does: carries effect state across a fork, so an effect can say how its state splits into a
forked computation and how the results rejoin. `Async` and `Fiber` are built on it, so nothing above
the kernel that forks can migrate until it exists.

The proto has adjacent machinery in `Handler.HandlerContext`: `fork(current: State): State < S`,
`join(current, forked, result): Result[Nothing, State] < S`, and `resolve(outer: Maybe[State]):
State`. Whether those are the isolation surface in a different shape, or only the context-effect half
of it, is the first thing to establish.

### B2. Partial evaluation and preemption, without a `Park` node

`Eval.partial`, parking and resumption are absent from the proto. The `Safepoint` substrate is
already there and unused: `arm`, `stopped`, `consumeStopped`, `beginSlice`/`endSlice`, and
`deadline` all exist on both platform variants with no caller.

**`Park` as a node kind is not wanted.** The reference needs one because its region stack is external
to the computation, so parking must snapshot it:

```scala
final private[kyo] class Park[+A, -S](
    val value: A < S,
    val entries: Span[Arrow[?, ?, ?]],
    val states: Span[Maybe[Any]],
    val marks: Span[Int],
    val finalizers: Span[Maybe[Finalizer[?, ?]]]
) extends Kyo[A, S]
```

and resuming is `stack.restore(entries, states, marks, finalizers)`.

The proto does not have that problem. A region there is already a value: `Kyo.Handle` *is* the region
installed around a computation, and the eval's foreign-suspension rebuild already turns an open region
back into one when a suspension is foreign to it (`Eval.scala:136-212`, whose `reenter` at `:140-147`
is the re-installation itself). There is no `Eval.crossing`: an earlier draft of this document named
one, and the name was invented. So parking is "walk the region stack outermost-in and rebuild each entry
into a `Handle`", which yields an ordinary computation value resumable anywhere, on any thread, with
no new node kind and no snapshot arrays.

The cost to weigh: rebuilding N regions allocates N nodes per park, where the reference copies into
spans. Which is cheaper depends on park frequency against region depth, and that is a measurement,
not a decision.

### B3. `EffectTrace`

`kyo/kernel/internal/EffectTrace.scala`. Splices effect frames into an exception's stack trace so a
failure points at the operation that caused it rather than at kernel internals. The proto throws raw.

Note the proto's `Frame` is already threaded through the surface (`ask(using Frame)`), so the
information is present; what is missing is capture and splicing.

### B4. `Mask`

Landed (2026-09-01 correction): the proto carries it in `ArrowEffect` (`Mask[E]` and
`maskTag`, `ArrowEffect.scala:486`), not as a separate `Mask.scala`, with
`ArrowEffectMaskTest` covering it. The original note ("absent from the proto") predated
that and is superseded.

### B5. `handleFirst` / `dispatchFirst`

`ArrowEffect.handleFirst` answers only the first operation of a region and hands the clause the raw
remainder, with the effect still in its row.

Its carrier, `FirstSuspended`, is **already internal** in the reference (`abstract private[kyo] class`
with `def input: Any` and `def cont: Arrow[Any, Any, Any]`), and should stay internal in the proto,
under `kernel/internal/`. It escapes `handleCont`'s expansion through the completion lane and
`handleFirst`'s done lane unwraps it before anything else observes it. It is abstract so each
expansion implements it anonymously, which keeps a primitive input unboxed.

Its second role in the reference does **not** carry over: the completion path checks
`!r.isInstanceOf[FirstSuspended]` before draining orphaned finalizers, and the proto has no finalizer
registry (see R3). So in the proto it is purely `handleFirst`'s protocol token.

## Ruled out, with the reasoning

### R1. `Catching` node kind and `Effect.catching` / `ArrowEffect.handleCatching`

Not needed. Handlers have `recover` now, which is consulted with the region still installed and its
live state. The scoped `catching` form has no proto equivalent and is not wanted.

### R2. `Binding` / `Bindings` node kinds

Not needed. Contextual values already exist in the proto as `ContextEffect` with `Context` threaded
through the eval loop, plus `HandlerContext` for the binding's own resolution.

### R3. `Finalizer`

Not needed at the kernel level. The kernel's support is `Handler.release`, the abandonment signal
consulted when a holder gives up on an extent's continuation. `Sync` becomes the bracketing layer
above it.

Consequence to carry forward: the reference's orphan machinery (`pushFinalizer`, `drainFinalizers`,
`drainOrphans`, `orphanOutcome`) has no proto counterpart by design, which is what makes B5's second
role moot.

Ruled, closing the abandonment-lane question the held-out analysis raised: releases never ride in
continuations, by construction. `Sync` is handled last, so its region is the outermost and no
handler ever stands outside it to be crossed toward; and `Handler.release` stays private to kyo
(the public `handle*` surface takes no release), so users cannot mint a release-owing region.
A clause dropping its continuation or a `Loop.done` dropping `next` therefore only ever discards
regions whose release is the default no-op. The crossing machinery that composes releases into
rotated transforms serves the one legal direction: the outward escape past everything, where the
release lands in a value held by kyo's own fiber machinery, which speaks it through `Eval.release`
on cancellation. Park inherits the same property.

### R4. Platform splits, `DebuggerPlatformSpecific` and `StackPlatformSpecific`

Judged not needed. Recording what they buy so the decision is revisitable: the reference uses them to
put the debugger cell and the stack pool behind `@static` on jvm-native and a plain module var on
js-wasm, where a single thread makes a thread local pointless indirection. The proto's stack pool
currently uses a plain `java.lang.ThreadLocal` in shared code, which compiles and runs on all three
platforms. So this is a per-platform optimization, not a correctness requirement.

## Open questions the backlog does not settle

### Q3 to Q6. Forks surfaced by the kernel test-suite port (added 2026-09-01)

Each holds a known-red pin set in the proto suite until ruled; the pins are the reproduction.

- **Q3. Park resume and binding re-resolution: RULED, the proto is the correct scoping**
  (2026-09-01, "the proto behavior seems the correct scoping"). A binding scopes the
  reads inside its body; a park is a snapshot of that body, so the captured state is what
  resumes, wherever it resumes. The kernel's re-resolution let a binding enclosing the
  resume site rewrite a lexically inner one. The four pins now pin the proto's law
  (ContextEffectThreadingTest x2, IsolateTest "a restored crossing reads the binding it
  captured").
- **Q4. `handleFirst` and brackets: RULED, the proto is the correct scoping**
  (2026-09-01, "keep the proto behavior"). A bracket lives as long as the region that
  answers the suspensions inside it. `handleFirst` ends that region with its
  `FirstSuspended` token, and the exit drains what the region owes, so the remainder the
  clause receives finds its brackets released: every branch is refused with `kyo.Closed`,
  the first included. The kernel's alternative, the token carrying its owed dumps until
  resumed or released, cannot rule out a holder dropping the token and leaking the
  resource. The clause itself still runs inside the region, so it sees the resource open;
  the region exits when the clause's value is delivered, and the remainder that value runs
  is refused at its first branch. The pin asserts exactly that order (EffectBracketTest
  "a handleFirst clause runs before the release its remainder runs after").
- **Q5. EffectTrace fidelity: DONE, with one ruling** (2026-09-01). Every site that hands
  user code to the evaluator on a cold path attaches what it was applying: the cont and op
  handlers attach the continuation with the suspension node, the loop handler attaches the
  continuation and the suspension's frame when its clause or the answer's application
  throws, and the unhandled path attaches the node. The carrier remembers the last stack it
  walked, so the unwind's re-attach adds no duplicate regions. EffectTracePhysicalTest is
  ported to jvm-native. The settled dispatch attaches nothing, ruled "let's remove the
  try/catch then": a handler over the megamorphic apply inside the loop body cost +27% on
  deferBindUnderTrailingMap (31.1 to 39.5 us at 3 forks), the same with a handler touching
  only the stack, so the cost is the handler's presence; recording the arrows per iteration
  on the pooled Stack retained them past the eval and is rejected for good ("DO NOT GO BACK
  TO THESE LEAKING FIELDS"). At that site the throwing step is still on the JVM stack, so
  the physical trace names it by file and line, and the two no-region pins assert exactly
  that; the effect trace carries what the physical stack cannot see, suspension boundaries
  and regions.
- **Q6. The `Kyo` combinator companion: DONE** (2026-09-01, "let's port Kyo.scala from
  the old kernel"). `kyo/proto/Kyo.scala` carries the companion, comments stripped; the
  internal node base `trait Kyo` moved into KyoInternal.scala beside the internal object,
  so `kyo.proto.Kyo` is only the user-facing companion. KyoTest, KyoForeachTest, and
  KyoForeachCollTest port with the toString pin adapted to the proto's rendering.
- **Q7. The rows the KernelBench port surfaced** (2026-09-01, `reviews/bench/three-kernel-board.md`).
  Three rows were red against both older kernels. `foreignCrossingsPayRotation` was 1,430 us
  against main's 325 and kernel2's 377 ("the proto kernel should have the best performance
  for crossing handlers since it doesn't use rotation"). Diagnosed and partly closed the
  same night, one variable per measurement, 3 forks:
  - A resumed crossing never settled the debt its dump left, so the answering region's lane
    kept one snapshot per resumed crossing until the exit drained them all, and a raw
    release hook fired twice for a region resumed and then unwound. `installed` now settles
    the lane's last debt in O(1): 1,399 to 1,097 us, the double release gone (its pin
    flipped to one release).
  - A foreign loop handler answering with a settled value dumped the inner regions, built a
    crossing step, a Defer and a Park, and re-installed the same regions, although nothing
    ran with them absent. Each arm now dumps only when something has to run outside the
    inner regions: the settled answer continues in place. The proto-only twin
    `foreignCrossingsAnsweredInPlace` measures 603 us and 1.52 MB/op; a bracket pin holds
    the resource live across the in-place answer.
  - What remains on `foreignCrossingsPayRotation` (1,092 us, 2.24 MB/op) is the eager
    capture a cont handler's crossing materializes per operation: the snapshot array, its
    lane node, the crossing step, the Defer and the Park, about 150 B and the sampled
    majority of the time, where rotation swaps two entries in place. JFR on the row
    attributes a third of the samples to the stack's dump, append, settle, and push, and
    the allocation samples name exactly those five classes. The direction on record since
    kernel-bench-comparison.md applies: borrow the segment in place and copy only on escape
    or on a second resume, so a crossing resumed inside its own clause captures nothing. That
    is a representation change for the ruling, not a fast path.
  Still open as measured: `fusionAfterSuspension` at 131 us against main's 88 (kernel2 was
  272), and its run-only twin at 0.53 us against 0.28, the ten maps stored on an unanswered
  suspension; `partialSuspensionBaseline` at 109 us against kernel2's 82 and the proto's own
  unarmed 77, the armed safepoint poll's price per answered operation.
  `sharedHandlerPaysDispatch` (1.18x main, at kernel2's level) and
  `userTypesSkipKernelWrapping` (1.15x main, allocation identical) sit with the boxing row.

Ported as the laws of the proto's own representation (2026-09-01, "I had asked you to do
that"): StackTest pins the pooled stack's four lanes, `find`'s tag subtyping, `truncate`,
`dump` and the owed lanes, `snapshot`, `contextual`, growth, and the pool; HandlerTest pins
`done`, `recover`, regions built on a handler, `answers` on settled, pending, and throwing
clauses, the trace attaches of `answering` and `running`, and the three arms of
`clauseDispatch`; DebuggerTest pins the hook defaults, install and uninstall, and that a
session observes an eval exactly when `Debugger.enabled` compiles the hooks in (it is an
`inline val`, false today, the Q1 decision). MaskTest's cases all live in
ArrowEffectMaskTest, one renamed for the recover arm. The fifteen KernelBench rows without
a proto twin are in ProtoBench under their own names, `partialSuspensionBaseline` included.
A case-level audit of the 155 old cases with no exact-name twin (2026-09-02,
`testport-audit-effect.md`, `testport-audit-arrow.md`) found 55 twins under other names, 22
missing, and 18 divergent. The 22 are ported: defer over an effectful body and a pending
value, defer composed with a recovery, the recovery cases that used `Effect.catching`
rewritten over a recovering region (type dispatch, throws after a `handleFirst`, a stateful,
and a resumed stateful region, past the budget rescue, outside a boxed computation, across a
park), the two-shot and ten-shot `handle` overloads, abandonment of a park with nothing owed,
the stateful node in the `dispatchFirst` walk, the two cross-region isolation pins, the two
throwing-release safepoint pins, nested evals' regions innermost first, and the fused
region's trace. Main's `ContextTest`, absent from both kernels, is ported too.
Divergent by construct or ruling, with the reasons in the two audits: the effectful and
`Result`-carrying releases (the proto's release is `(A, Maybe[Throwable]) => Unit`), the
refusing and effectful `fork` strategies (the proto's `fork` is total), `Effect.catching` and
the by-name `handleCatching`, `Context.inherit` and `Noninheritable` (the proto's
`handleInheritable`), re-entering a spent bracket (refused with `kyo.Closed`), the
`handleFirst` remainder (ruled Q4), the park-resume binding (ruled Q3), a `done` throw
reaching its own recovery, and the physical-trace law for throws with no region standing
(ruled Q5).

### Q2. Nesting in `Loop.Outcome*` (added 2026-09-01)

The pending union handles a computation used as data with the nest-once contract: `Boxed`
payloads are `Nested`-wrapped at the lift so the eval cannot mistake a payload for a
suspension. `Loop.Outcome2` has the same structural exposure and no equivalent guard is on
record: the eval and the staged dispatch discriminate an outcome by class
(`case c: Continue2 => ...; case pending: Pending => ...; case done => done as B`), so a
done payload `B` that is itself a `Continue2` (a loop whose result is another loop's
outcome, or `B = Any` holding one) would dispatch as a continue, and a `B` that is a
`Pending` (a computation as data in the done position) would dispatch as the clause's
suspended outcome. The audit: whether these shapes are reachable through the public
`handleLoop` / `Loop.done` surface, and if they are, the fix family is the one `<` already
uses (a marker bound like `Boxed` closing the channel, or nest-once at the outcome
boundary), plus hostile pins: done-of-`Continue2`, done-of-pending, `B = Any`. The old
kernel's `Continue extends Serializable` design note is the prior art for proving an arm
unreachable instead of guarding it.

Closed (2026-09-02). The guard is the one `<` uses, applied at the outcome boundary:
`Loop.done` wraps a `Continue*` payload in `Done` and nests every other payload
(Loop.scala:124-140), so a done payload is never confused with a continue or with the clause's
suspended outcome. Pinned from the public surface: LoopTest "outcome payloads that are outcomes"
(a done payload that is itself a `Continue`, one of type `Any` holding a `Continue`, a suspended
done payload that is a `Continue`, an `Any`-typed payload holding a computation),
PendingTest "a loop can end its region with a computation result" and its effectful twin, and
ArrowEffectTest "an Any-typed Continue2 does not conform to the clause's outcome without
Loop.done" for the compile-time closure.

### Q1. The debugger gate

`Debugger.enabled` is an `inline val` and instrumenting a build is a source edit. Measured cost with
it on: `suspensionBaseline` 256.9 us/op against 126.0 with it off, and `statefulAnswersPaySuccessor`
387.9 against 194.0, because five node and arrow constructors carry `Debugger.onAlloc(this)` and the
reference has no per-allocation hook at all.

That is a build-configuration problem rather than a missing capability, but it touches every item
here: any of this work measured on a build with the gate on will be measuring the gate.

Resolved (2026-09-02, "the debugger mechanism must not leave ANY footprint in the bytecode.
You can NOT use @static it must be compile time"). `Debugger.enabled` is
`CompileTimeFlag.boolean("kyo.proto.kernel.internal.Debugger.enabled", false)`, a macro in
kyo-data's `kyo.internal` that reads the system property in the compiler's own JVM at
expansion and expands to a literal, so `inline if enabled` folds and the hooks compile to
nothing when off. Enabling it is a compiler-process property, `sbt
-Dkyo.proto.kernel.internal.Debugger.enabled=true ...`, and a rebuild. Verified in
`164eb68f47`: `javap` finds zero `Debugger` references in `Eval$`, `Handler$` and the `Defer`
node with the gate off; DebuggerTest green.

## Soundness findings (added 2026-09-02)

Source: `soundness-audit.md`, the reading pass over the whole proto kernel. Twelve reproduction
tests are red on JVM, each failing on the value the finding predicted; the tip carrying them is
`28d1dcb43f`. Grouped by root cause, with the ruling and the fix direction. Status moves to
"fixed" when the named tests are green and the full suites are green on JVM, JS and Native.

### S1. `Loop.repeat` and `Loop.indexed` detect suspension by `Arrow`, not `Pending`

Ruling: an artifact of an older representation; fix. `Loop.apply` matches `Pending`; `repeat`
and the five `indexed` overloads match `Arrow`, which only `DeferWith` (from `Effect.defer(f)`
and `map`) mixes in. A `Handle`, a bare `SuspendArrow` or `SuspendContext`, a `Park`, or an
arrow-form `Defer` body is treated as settled: `repeat` skips it, `indexed` returns the node as
the loop's value. Tests: LoopTest "repeat suspends a bare operation each time", "repeat enters a
context region each time", "repeat acquires a bracket each time", "indexed loops a bare operation
whose answer is an outcome". Fix: the `Pending` arm in all six sites. Status: fixed in `dcb877587f`, verified on JVM, JS and Native.

### S2. A context region's exit re-adds a key it does not own

Reads (`Context.get`, a `TypeMap` lookup by `<:<`), entry (`derive(ctx.get(tag))`) and
`Stack.find` are all subtype-aware; `Context.remove` is exact. `contextExit` and `rebound`
recompute the binding after a pop with `stack.find(tag)`, so an inner `Cfg` region exiting under
an outer `CfgSub` region finds the outer and writes an exact `Cfg` key with the outer's state;
the outer's exit removes only `CfgSub`, and `Cfg` stays bound for the rest of the eval. Test:
ContextEffectTest "an inner region at the supertype tag leaves no binding behind once its outer
subtype region exits". Fix: the recomputation at exit and in `rebound` walks the stack for the
exact tag; when none is found the exact key is removed and the subtype-aware read still sees the
outer region's own key. Recomputing from the stack (rather than saving the prior binding at
entry) is the right shape because the stack is the source of truth once S3 updates a region's
state in place. Status: fixed in `ccafba44c9` (`Stack.findExact` at exit and in `rebound`), verified on JVM, JS and Native.

### S3. The Contextual isolate's join lands on copies of the regions

`Isolate.internal.Contextual.restore` merges the snapshots and installs the joined entries as
fresh regions through a `Kyo.Park`, above the originals. The joined state lives in the copy: reads
inside the restore's continuation see it, the copy's exit rebinds from the original's untouched
state, and each copy fires the user's `done`. Tests: IsolateTest "a merging join outlives an arrow
region that closes after the restore", "an isolate cycle fires done once, for the region the user
installed". Fix direction: apply the joined state to the owning region's slot on the live stack
(the region found by handler identity) and update the context, pushing nothing; the evaluator's
`Snapshot` arm has to give the node the means to do that. Needs a derivation before the edit.
Ruled: fork and join copies are silent to the user's hooks; only the region the user installed
fires `done` and `release`, with the joined state. Fixed in `1ede100a1b`: the `Snapshot` node's
continuation receives the live stack, `restore` writes each joined state into the origin's slot
by handler identity and continues in place, the evaluator rebuilds the context after the node
(the rebuild `guarded` already had), and fork copies carry a `Forked` handler that delegates
`tag`, `derive`, `fork` and `join` and inherits the no-op hooks. Verified on JVM, JS and Native.
Consequence for the Q3 pins: "a restored crossing reads the binding it captured" was green by
arithmetic coincidence (the outer region's `1 + 1` join copy pushed above the restore scope, not
the inner binding). Ruled 2026-09-02: a read placed after a restored crossing sees the scope it
restores in, and the join lands on the live region; the pin now asserts 10 inside that scope and
the joined 2 once it exits (`66294c5fca`). Status: fixed.

### S4. A debt re-homed below the answering region is never settled by the resume

A crossing owes the dumped regions in the answering handler's lane so a clause that never
resumes still releases them. `installed` settles the debt only in the top lane. When the foreign
loop clause's outcome is pending, the handler pops and `oweBelow` moves its lane one down; when
the clause resumes inside a nested region, the top lane is that region's. Either way the resume
misses the debt, the region completes and fires `done`, and the drain at exit fires `release` on
it with the "remainder discarded" failure. Brackets are masked by the Cell. Tests:
ContextEffectTest "a region crossed to a foreign loop answered with a pending outcome completes
without a release", "a region crossed to a foreign clause that resumes inside a nested region
completes without a release". Fix: settle by snapshot identity in whatever lane holds the debt.
Status: fixed in `ccafba44c9` (`Stack.settle` by snapshot identity across lanes), verified on JVM, JS and Native.

### S5. A `ContextEffect.handle` node derives its state on every read, and its settled arm derives from nothing

`def state = h.derive(Maybe.empty)` reruns `derive` for every `Eval.release` and `toString`; the
settled arm runs `done(derive(Maybe.empty))` when the expression is built, blind to the
enclosing binding. Tests: EvalTest "an abandoned region value derives its state once and releases
that state", ContextEffectTest "a settled body still derives from the binding around it". Fix:
derive once per node, and make the settled arm observationally equal to the deferred one (the
region node, entered under the enclosing binding). Status: fixed in `ccafba44c9` (one region node for both bodies, state derived once), verified on JVM, JS and Native.

### S6. The effect trace dedupes by the identity of the pooled `Stack`

`EffectTrace.reconstruct` skips the walk when `seen eq stack`; the stack is the per-thread pooled
instance the next eval borrows again, so a failure rethrown through a later eval on the same
thread never gets that eval's regions. Test: EffectTraceTest "a failure rethrown through a later
eval on the same thread names the later eval's region". Fix: dedupe per eval, not per stack
object. Status: fixed in `ccafba44c9` (a per-eval epoch on the pooled stack), verified on JVM, JS and Native.

### S7. Nested `Eval.partial` (dropped)

The reading pass predicted that a stop consumed by a nested `Eval.partial` is lost to the
enclosing slice. Ruling: the scheduler contract has no nested partial; `Eval.partial` is called
only by the task loop. The test was removed; no change.

### S8. A child copy's re-entry check was silent, and the bracket forked an inert cell

A continuation captured inside an isolated child under a bracket, with the answering handler
between the bracket and the isolate so the crossing dumps only the copy, and resumed after the
bracket ended, ran the `use` code on the released resource: the copy's state was `Cell.inert`
(exempt from the re-entry check) and `Forked` inherited the no-op `reenter`. Test:
EffectBracketTest "a capture inside an isolated child, resumed after the bracket ended, is
refused" (red: 8 where `Closed` was due). Fix in `164eb68f47`: `Forked` forwards `reenter` to
its origin, the bracket forks the live cell (copies are silent to `done` and `release` under
the S3 ruling, so the inert cell had no purpose left), and the re-entry check drops its
exemption. Status: fixed, JVM green (2449); JS and Native deferred to the final sweep
("no need to keep running js and native for now, we do a sweep at the end").

### S9. A crossing resumed in a nested eval is released again at the owner's exit (from the eff issue 12 audit)

`reviews/proto-migration/eff-issue-12-audit.md`, entry 1. The cross-eval twin of S4: a
clause that resumes a crossing inside a nested eval (`answerAsk(0)(cont(41)).eval` inside the
clause) re-installs the dump on a different pooled stack, whose `settle` cannot see the debt
in the outer eval's lane; the owner region's exit drains it and `release` fires after `done`.
The same path with a race is a resume handed to another thread. Fix shapes, ruling pending:
(1) the debt is discharged on the snapshot itself, `installed` marks it consumed and every
drain skips a consumed snapshot, which subsumes S4's lane scan and covers nested evals and
other threads (recommended); (2) a stack remembers the stack active on its thread when
borrowed and `settle` walks that chain, nested evals only; (3) rule raw hooks at-least-once
across evals and pin the release. Status: open, no test yet.

The audit's other fifteen entries are predicted green or already ruled: thirteen laws with no
pin today (local handlers not in scope for a clause, computations as answers, a suspending
clause travelling with its continuation, bindings above and below the answering handler,
recoveries captured with a continuation, per-shot hooked regions, `handleFirst` remainders
and raw context regions, brackets below the answering handler, isolates crossed and parked,
parks inside clauses) and two ruled by existing pins. Two of the thirteen raise rulings:
entry 7, a loop clause's post-suspension throw escapes its region's own `recover` while the
same code under `handleCont` is caught, which follows the clause signatures; entry 13, an
isolate resumed under a different region of its tag joins nothing, the identity law. Both
are recommended as pinned. Pins pending.

Report finding 21, the `scratch` store on the pooled stack, is not a defect: it is the measured
escape that defeats a C2 scalar-replacement pathology on the settled loop rows (`a8cff0a3f8`,
363 to 155 us on `handleLoopAnswersInPlace`), and it stays. The nine coverage pins (findings 12 to 20)
are added as tests in the "reading audit pins" groups of ArrowEffectTest, ContextEffectTest,
EvalTest and EffectBracketTest, all green. Report finding 10, the physical frames cached from
the first splicing thread, is not a defect: a `Throwable` captures its stack trace at
construction, so a shared instance thrown on another thread never carries that thread's frames
with or without the cache; the cached tail is the construction site's, consistent with the JVM.
EffectTraceThreadingTest pins that law: the physical tail is identical after a splice on a
second thread and the carrier count stays one.
