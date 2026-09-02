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

Divergent by design, not ported: HandlerTest (handler-as-arrow, `Handler.Out`),
DebuggerTest (the session protocol, see Q1), StackTest (the flattened arrow stack), the
`Effect.catching` node cases, the by-name `handleCatching` case, and the refusable
effectful `fork` cases.

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

### Q1. The debugger gate

`Debugger.enabled` is an `inline val` and instrumenting a build is a source edit. Measured cost with
it on: `suspensionBaseline` 256.9 us/op against 126.0 with it off, and `statefulAnswersPaySuccessor`
387.9 against 194.0, because five node and arrow constructors carry `Debugger.onAlloc(this)` and the
reference has no per-allocation hook at all.

That is a build-configuration problem rather than a missing capability, but it touches every item
here: any of this work measured on a build with the gate on will be measuring the gate.
