# kyo-kernel2 backlog

Working doc. Sections in priority order: what needs you, what is running, what I can pick up, what is deliberately
set aside, what is finished.

---

## 🔴 Blocked on the owner

An item lands here only when it is finished and waiting on you, not while it is still being worked.

Nothing right now. E1 moves here when its review lands.

---

## 🟡 Executing

### E1. Bracket and Park

Design written (`reviews/BRACKET-PARK-DESIGN.md`), held-out review running. Moves to blocked when the review lands
and it is ready for your approval. **Implementation will not start without that approval.**

Two decisions it will bring you:

1. **Is `Effect.bracket` `inline`?** Design ships it non-inline; prior art was `inline`
   (`3a95636fa8:Effect.scala:56`). Not a free choice: an inline `bracket` mints `new Kyo.Bracket` at the call site,
   so `Kyo.Bracket` must be nameable from an expansion site outside package `kyo`, the same constraint that produced
   the unqualified `Arrow.Transform` imports. That makes it a requirement on `KyoInternal.scala`.
2. **`finalizeResources` visibility.** Design ships `private[kyo]` per prior art; the brief called it a public
   extension on the pending type.

One limitation it reports rather than hides: a `handleCont` clause that receives the interior fold and neither
applies it nor throws leaks the resource. Inherent to a continuation delivered as a `Function1`. Pinned as a
specification, with `Scope` named as the correct home.

### E2. Visibility reduction

`Kyo`, `Stack`, `Safepoint`, `Handler` and `Nested` narrowed to `private[kyo]`. `EffectTrace` stays public by your
decision, since it becomes `KyoException`.

kernel2 compiles and all 13 fixtures compile from outside package `kyo`. The unqualified imports added for
`Arrow.Transform` already cover the reference sites, so no further reference pass was needed.

`private[kernel]` is not reachable for `Kyo` or `Safepoint`: `Arrow.scala` is `package kyo`, not `kyo.kernel`, and
names both directly. `private[kyo]` is the floor for those two.

**One cost surfaced, and it is a signal rather than a nuisance.** The narrowing pushed `KyoTest`'s
`assert(widen(TypeMap(1, true)).eval.get[Boolean])` past the JVM's 64KB string-constant limit: `eval` expands the
whole drive inline, and scalatest's `assert` renders its argument into a constant. Binding the value before the
assert fixes it and is the better test regardless, but the underlying fact stands: the drive expansion is large
enough that adding accessor indirection to it crosses a hard JVM limit. That belongs to P1.

---

## 🟢 Ready for execution

### R1. Re-run the runtime benchmark board

**The board I gave you this morning is stale.** It predates the unqualify change, which touched `Pending.scala`,
`Eval.scala` and `ArrowEffect.scala`, all hot path. Same rows, `-f 2`, `-prof gc`.

### R2. `map` expansion tree dump

Does not need the machine, so it fills time while runs are in flight.

Static answer is in hand: `f`, `unsafeGet`, `self` and **`lift`** expand; `Effect.defer`, `Safepoint.*`, `Arrow.id`,
`next.head/tail` do not. What is left is size, on three lambda shapes: bare value, bare singleton (which reaches the
`CanLift` splice macro), already-pending.

### R3. Recursion benchmarks and the `Loop`-on-`Arrow.recursive` question

Six rows, `Arrow.recursive` vs `Loop` vs plain recursive `def`, pure and effectful, then the design question in the
same breath since the numbers are the argument. Allocation is the discriminator: `Loop.continue` mints a `Continue`
per iteration, `Arrow.recursive` mints one arrow total. Ends in a proposal, not a change: `Loop.continue`/`done` at
four state values is public surface.

---

## ⚪ Parked

### P1. The inline sweep
FB unpark this. I'll step out please work autonomously and focus on optimizing compilation time without regressing runtime performance
`CanLift` givens · `Kyo.lift` and `Kyo.unit` to `@static` · `inline self` on the pending extension ·
`evalNow`/`unsafeGet` binding `self` once. One experiment at four sites, all asking the same question: what does
dropping `inline` from a kernel primitive cost at runtime and buy at compile time. Bytecode pins first, since if the
settled-path fold disappears the sizes answer it before JMH runs.

Two findings already established and worth keeping when this restarts. `derivedSingleton` must stay inline
regardless: as a plain given its macro would expand against an abstract type, `checkImpl`'s two guards would both
evaluate false, and the module lint would silently become a no-op rather than failing loudly. And `Kyo.unit` is the
weakest candidate of the four, since its body is a literal `()` and `@static` would add an `invokestatic` where
today there is nothing at all.

`evalNow`/`unsafeGet` binding `self` once is also a latent correctness fix, not only a perf question: `inline self`
substitutes the receiver expression at each use, and both methods use it twice, so `v.map(...).evalNow` re-expands
the whole chain.

### P2. `EffectTrace` becomes `KyoException`

`EffectTrace` is an `Exception`, but it is not the exception that gets thrown. Today it is a **carrier**: the drive
attaches it to whatever crossed, via `addSuppressed`, and rewrites that exception's own trace with the effect
frames. A user's `RuntimeException` keeps propagating as itself.

The owner's proposal is to wrap instead: a throwable crossing a drive becomes a `KyoException` carrying the effect
frames, with the original as its `cause`. `KyoException` already takes `cause: String | Throwable`
(`KyoException.scala:26`), so this needs no new surface, and it is genuinely one mechanism rather than two. That is
a better answer than the two-mechanism framing I first gave.

The cost to weigh when this restarts: **wrapping changes what user code catches at every drive boundary.**

```scala
try Eval(computation)
catch case e: MyDomainException => recover(e)   // matches today, would not after wrapping
```

The case to check hardest is `Abort[E]` with a user exception type as `E`. If recovery matches on the exception
type and the drive has wrapped it, recovery silently stops firing.

Other blockers identified but not yet verified: `KyoException` extends `NoStackTrace` and `splice` deliberately
skips exactly that class; the `getMessage` change reaches 32 subclasses across 20 modules, including a standing
warning in `kyo-test`'s `Assertion.scala` where this already bit someone; `KyoException` lives in kyo-data, below
the kernel that would fill it; a fatal error must not be swallowed into a wrapper, which `EffectTraceTest` pins;
and an exception crossing three nested drives must not be wrapped three times.

### P3. `Effect.catching`

Needs a stack entry the drive consults while unwinding. CPS let the old kernel wrap the continuation and thereby
guard everything downstream, because in CPS the continuation *is* the rest of the computation. Here the drive owns a
stack and the rest is spread across its entries, so a `try` inside one arrow's `apply` guards building the next
deferral rather than running it. Two workarounds were traced and both fail: a self-reinstalling guard arrow does not
guard the drive's own evaluation, and a structural rewrite of every continuation pays an allocation per drive step.

Same mechanism as Bracket's release-on-throw, so it rides on that work rather than duplicating it. Signature is
recorded commented in `Effect.scala` with this reasoning.

It also owes a tracing contract, established during the EffectTrace port: `splice` currently runs only at the drive
boundary, which assumes an exception is observed only there. A catching handler is a second observation point, so it
must attach and splice before calling `f`, or the handler sees frames in the carrier that are absent from the stack
trace.

### P4. `Eval.partial`

Lands with Bracket and Park, since a park is what a partial drive hands back. Consumers already written and waiting:
the commented partial-evaluation group in `EvalTest`, two cases in `ArrowEffectTest`, and the stop-observability
case in `SafepointConcurrencyTest`.

### P5. `handleFirst`, `dispatchFirst`, `handleCatching`, `handlePartial`

Signatures recorded commented in `ArrowEffect.scala`, marked `private[kyo]`.

Each waits on something different. `handleFirst` has no primitive to stand on: the previous kernel built it on a
stateful `handleLoop` whose clause received the continuation and used `Loop.done` to carry the resumed remainder
out, and neither `handleCont` (which keeps the region installed) nor `handleLoopState` (which answers with a value)
substitutes. `dispatchFirst` and `handlePartial` wait on the IOTask integration design, and `handlePartial`
additionally needs partial evaluation to hand back a resumable value. `handleCatching` wants the same unwind
mechanism as P2.

### P6. `ContextEffect` and `Effect.detach`

A whole feature absent from this kernel, not a gap in an existing one. Parked test corpora reference it in
`EffectTest` and `PendingTest`, kept commented against the replacement design.

### P7. Bench coverage audit

Mapping the 17 benchmark rows to the computation shapes they exercise, then surveying what `kyo-core`,
`kyo-prelude`, `kyo-http`, `kyo-actor` and `kyo-stm` actually build on the kernel, and producing the gap list:
shapes common in practice with no row, and rows measuring shapes nobody writes. Matters because every performance
decision in this campaign is keyed to those 17 rows.

### P8. The deferred block's own frame in a trace

A throw from the body of `Effect.defer` happens while the drive reads the node's payload, the one path into user
code the eight attach sites do not cover. Guarding it would put a `try` region on the deferral arm, the hottest arm
of the drive, to describe a failure on a surface that carries no frame of its own, since `Kyo.Defer` declares none.
The exception propagates correctly; it simply arrives without effect frames.

### P9. `Arrow.step`

Dropped for good, not deferred. `head` and `tail` are on `Arrow` itself and say the same thing, so the case was
deleted rather than parked.

---

## ✅ Done

| what | evidence |
|---|---|
| Proto adopted as the kernel; test corpora merged from proto, previous kernel2, and kyo-kernel | **789 tests green**, clean batch build, JS and Native compile |
| `Loop` dispatched on the old node type | All 13 combinators detected pending with `case arrow: Arrow`, dead in this kernel. `Loop.apply` returned a suspended outcome as the result; `whileTrue` span forever. Found by a hang in a test that had never run. |
| `Effect.defer` by-name restored | Lost in the move; re-expressed over `Kyo.Defer` whose payload is a method |
| `EffectTrace` ported | Value position vs arrow position is what makes the six self-referential continuation slots terminate; eight attach sites, boundary splice |
| `map` and `Eval` did not compile from outside package `kyo` | Caught only by the compile-bench fixtures, the sole code outside `kyo`. Fixed by unqualified imports, **not** by widening visibility. |
| Compile-bench harness repaired | `fixtures-proto` pointed at a removed package. Now one shared corpus against both classpaths, with an override only where the surface genuinely differs. |
| Trivial surfaces restored | `handleLoopState` overload without `done`, `toString` on nodes and arrows, `Render` for the pending type, `ArrowEffectBytecodeTest` revived with measured sizes |
| Commented signatures for missing surface | `Effect.scala`, `ArrowEffect.scala` (marked `private[kyo]`), `Eval.scala` |
| Compile-time board against kyo-kernel | 13 fixtures, both kernels, 8 warmup, `-prof gc`. kernel2 faster on 6 of 13, materially on 4 (`SuspendSites` 0.68x, `TagDerivation` 0.71x, `ForCompDeep25` 0.81x, `NestedMaps` 0.83x). Raw JSON in `bench-results/compile-0820/` |

---

## Open questions carried forward

1. `suspensionBaseline` is red against the last proto board and **not accepted**. Same-session isolation attributed
   2.8 of 9.6 points to the EffectTrace wiring; the residual is unexplained and the comparison is cross-session,
   which cannot support a claim at that size. R1 re-measures.
4. `HandleSites` compiles 1.49x slower on kernel2 and allocates 1.26x more. It is the one fixture with a per-kernel
   override, so the delta carries a source difference (`handle` with one clause and `.eval` versus `handleCont` with
   a done clause under `Eval`). Together with `handleCont`'s 87-byte call site, three signals point at region
   construction being more expensive. Wants its own look.
2. `handleCont` compiles to 87 bytes against the old kernel's 33 for `handle`. Not the same expansion, but the size
   says the call site allocates twice, the region node and the handler it holds, where the old region was one node.
3. The concrete-class lift moved from 2 bytes to 5. This lift has no emission analysis to prove a final class admits
   no payload, so a concrete class pays one union `instanceof`.
